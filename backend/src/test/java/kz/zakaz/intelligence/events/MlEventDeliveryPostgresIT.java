package kz.zakaz.intelligence.events;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

/** ML-R2 delivery RED: database claims, fencing, ACK, retry, and consumer idempotency. */
@SpringBootTest @Testcontainers @ActiveProfiles("test-pg")
class MlEventDeliveryPostgresIT {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcMlEventQueue queue;
    @Autowired JdbcMlEventProjectionSink sink;
    @BeforeEach void isolateDeliveryQueue() {
        jdbc.execute("TRUNCATE TABLE ml_event_projection_inbox,ml_event_delivery RESTART IDENTITY");
    }
    private record Fixture(UUID eventId,UUID demandId,UUID buyerId){}
    private Fixture demandEvent() {
        UUID id=UUID.randomUUID(),buyer=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO buyer_demand_snapshot(id,buyer_id,city,expires_at,created_at,version)
            VALUES (?,?,'Almaty',now()+interval '2 hours',now(),0)
            """,id,buyer);
        UUID event=jdbc.queryForObject("""
            SELECT event_id FROM ml_trade_event WHERE demand_id=? AND event_type='DEMAND_CREATED'
            """,UUID.class,id);
        return new Fixture(event,id,buyer);
    }

    @Test void twoWorkersCannotClaimOneEventAndAckMustBeFenced() throws Exception {
        var f=demandEvent();
        var pool=Executors.newFixedThreadPool(2);
        var gate=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{gate.await();return queue.claim("worker-a",5,Duration.ofSeconds(30),3);});
            var b=pool.submit(()->{gate.await();return queue.claim("worker-b",5,Duration.ofSeconds(30),3);});
            gate.countDown();
            var both=new ArrayList<JdbcMlEventQueue.Claim>();
            both.addAll(a.get(15,TimeUnit.SECONDS));
            both.addAll(b.get(15,TimeUnit.SECONDS));
            assertThat(both).extracting(JdbcMlEventQueue.Claim::eventId).containsExactly(f.eventId());
            var original=both.getFirst();
            jdbc.update("UPDATE ml_event_delivery SET lease_until=now()-interval '1 second' WHERE event_id=?",f.eventId());
            var reclaimed=queue.claim("worker-c",1,Duration.ofSeconds(30),3).getFirst();
            assertThat(queue.ack(original)).isFalse();
            assertThat(reclaimed.generation()).isGreaterThan(original.generation());
            assertThat(queue.ack(reclaimed)).isTrue();
            assertThat(queue.claim("worker-d",1,Duration.ofSeconds(30),3)).isEmpty();
        } finally {pool.shutdownNow();}
    }

    @Test void sinkBeforeAckThenWorkerCrashAndReplayProducesOneConsumption() {
        var f=demandEvent();
        var first=queue.claim("worker-a",1,Duration.ofSeconds(30),4).getFirst();
        sink.deliver(first);
        jdbc.update("UPDATE ml_event_delivery SET lease_until=now()-interval '1 second' WHERE event_id=?",f.eventId());
        var replay=queue.claim("worker-b",1,Duration.ofSeconds(30),4).getFirst();
        sink.deliver(replay);
        assertThat(queue.ack(replay)).isTrue();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_event_projection_inbox WHERE consumer_id='ml-r2-projection-v1' AND event_id=?
            """,Integer.class,f.eventId())).isEqualTo(1);
    }

    @Test void failuresBackOffThenDeadLetterAndExpiredFinalLeaseDoesNotRemainInFlight() {
        var f=demandEvent();
        var first=queue.claim("worker-a",1,Duration.ofSeconds(30),2).getFirst();
        assertThat(queue.fail(first,"temporary",Duration.ofSeconds(5),2)).isTrue();
        assertThat(queue.claim("worker-b",1,Duration.ofSeconds(30),2)).isEmpty();
        jdbc.update("UPDATE ml_event_delivery SET next_attempt_at=now()-interval '1 second' WHERE event_id=?",f.eventId());
        var last=queue.claim("worker-b",1,Duration.ofSeconds(30),2).getFirst();
        jdbc.update("UPDATE ml_event_delivery SET lease_until=now()-interval '1 second' WHERE event_id=?",f.eventId());
        assertThat(queue.ack(last)).isFalse();
        assertThat(queue.claim("worker-c",1,Duration.ofSeconds(30),2)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM ml_event_delivery WHERE event_id=?",
            String.class,f.eventId())).isEqualTo("DEAD_LETTER");
    }

    @Test void producerRollbackCreatesNeitherEventNorDelivery() {
        UUID id=UUID.randomUUID();
        assertThatThrownBy(()->new org.springframework.transaction.support.TransactionTemplate(txManager)
            .execute(status->{
                jdbc.update("""
                    INSERT INTO buyer_demand_snapshot(id,buyer_id,city,expires_at,created_at,version)
                    VALUES (?,?,'Almaty',now()+interval '2 hours',now(),0)
                    """,id,UUID.randomUUID());
                throw new IllegalStateException("forced rollback");
            })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_event_delivery WHERE event_id IN
            (SELECT event_id FROM ml_trade_event WHERE demand_id=?)
            """,Integer.class,id)).isZero();
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    @Test void eventDeliveryRespectsCausalOrderWithinOneBuyerDemand() {
        var f=demandEvent();
        UUID invitation=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO ml_seller_invitation(
                id,demand_id,seller_id,policy_version,status,reason_code,evaluated_at)
            VALUES (?,?,?,1,'SUPPRESSED','NO_ACTIVE_MEMBERSHIP',clock_timestamp())
            """,invitation,f.demandId(),UUID.randomUUID());
        UUID laterEvent=jdbc.queryForObject("""
            SELECT event_id FROM ml_trade_event
            WHERE aggregate_id=? AND event_type='SELLER_INVITATION_EVALUATED'
            """,UUID.class,invitation);
        var first=queue.claim("ordering-a",10,Duration.ofSeconds(30),3);
        assertThat(first).extracting(JdbcMlEventQueue.Claim::eventId)
            .containsExactly(f.eventId());
        assertThat(queue.ack(first.getFirst())).isTrue();
        var second=queue.claim("ordering-b",10,Duration.ofSeconds(30),3);
        assertThat(second).extracting(JdbcMlEventQueue.Claim::eventId)
            .containsExactly(laterEvent);
        sink.deliver(second.getFirst());
        assertThat(queue.ack(second.getFirst())).isTrue();
    }

    @Test void conflictingConsumerFingerprintIsRejectedInsteadOfSilentlyDeduplicated() {
        var f=demandEvent();
        var claim=queue.claim("fingerprint-worker",1,Duration.ofSeconds(30),3).getFirst();
        jdbc.update("""
            INSERT INTO ml_event_projection_inbox(consumer_id,event_id,fingerprint)
            VALUES ('ml-r2-projection-v1',?,?)
            """,f.eventId(),"0".repeat(64));
        assertThatThrownBy(()->sink.deliver(claim))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Conflicting replay fingerprint");
        assertThat(queue.fail(claim,"Fingerprint mismatch",Duration.ZERO,1)).isTrue();
        assertThat(jdbc.queryForObject("""
            SELECT status FROM ml_event_delivery WHERE event_id=?
            """,String.class,f.eventId())).isEqualTo("DEAD_LETTER");
    }

}
