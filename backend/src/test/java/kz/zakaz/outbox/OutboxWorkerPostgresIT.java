package kz.zakaz.outbox;

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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test-pg") @Testcontainers
class OutboxWorkerPostgresIT {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcOutboxQueue queue;
    @BeforeEach void isolateQueue() {
        jdbc.execute("TRUNCATE TABLE order_event_projection_inbox,order_outbox_event RESTART IDENTITY");
    }

    private UUID event(UUID orderId,String type) {
        UUID id=UUID.randomUUID(),line=UUID.randomUUID();
        new TransactionTemplate(txManager).executeWithoutResult(status->{
            jdbc.update("INSERT INTO customer_order(id,public_number,seller_id,buyer_id,status,total_amount,delivery_fee,currency_code,created_at,version,expected_line_count) VALUES (?,?,?,?, 'PLACED',100,0,'KZT',now(),0,1)",
                orderId,"ZK-"+randomFour()+"-"+randomFour(),UUID.randomUUID(),UUID.randomUUID());
            jdbc.update("INSERT INTO order_line(id,order_id,line_number,sku_id,quantity,unit,unit_price,line_total) VALUES (?,?,1,?,1,'PCS',100,100)",
                line,orderId,UUID.randomUUID());
            jdbc.update("INSERT INTO order_outbox_event(id,aggregate_id,event_type,payload,occurred_at) VALUES (?,?,?,'{}',now())",
                id,orderId,type);
        });
        return id;
    }
    private String randomFour(){return UUID.randomUUID().toString().substring(0,4).toUpperCase(Locale.ROOT);}
    private UUID additionalEvent(UUID orderId,String type){
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO order_outbox_event(id,aggregate_id,event_type,payload,occurred_at) VALUES (?,?,?,'{}',now())",id,orderId,type);
        return id;
    }
    @Test void concurrentClaimersNeverClaimSameEvents() throws Exception {
        var ids=new HashSet<UUID>();
        for(int i=0;i<20;i++) ids.add(event(UUID.randomUUID(),"ORDER_CONFIRMED"));
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{start.await();return queue.claim("worker-a",20,Duration.ofSeconds(30),5);});
            var b=pool.submit(()->{start.await();return queue.claim("worker-b",20,Duration.ofSeconds(30),5);});
            start.countDown();
            var first=a.get(20,TimeUnit.SECONDS);var second=b.get(20,TimeUnit.SECONDS);
            Set<UUID> claimed=new HashSet<>();
            for(var c:first)assertThat(claimed.add(c.id())).isTrue();
            for(var c:second)assertThat(claimed.add(c.id())).isTrue();
            assertThat(claimed).containsExactlyInAnyOrderElementsOf(ids);
        } finally {pool.shutdownNow();}
    }
    @Test void expiredLeaseCanBeReclaimedAndStaleGenerationCannotAck() {
        var id=event(UUID.randomUUID(),"ORDER_CONFIRMED");
        var old=queue.claim("pod-a",1,Duration.ofSeconds(30),3).getFirst();
        assertThat(old.id()).isEqualTo(id);
        jdbc.update("UPDATE order_outbox_event SET lease_until=now()-interval '2 seconds' WHERE id=?",id);
        var current=queue.claim("pod-b",1,Duration.ofSeconds(30),3).getFirst();
        assertThat(current.generation()).isGreaterThan(old.generation());
        assertThat(queue.ack(old)).isFalse();
        assertThat(queue.extend(old,Duration.ofSeconds(10))).isFalse();
        assertThat(queue.ack(current)).isTrue();
        assertThat(queue.claim("pod-c",1,Duration.ofSeconds(30),3)).isEmpty();
    }
    @Test void sinkDeliveryBeforeCrashThenReplayHasExactlyOneBusinessEffect() {
        var id=event(UUID.randomUUID(),"ORDER_CONFIRMED");
        var sink=new JdbcOrderEventProjectionSink(jdbc);
        var first=queue.claim("pod-a",1,Duration.ofSeconds(30),4).getFirst();
        sink.deliver(first);
        jdbc.update("UPDATE order_outbox_event SET lease_until=now()-interval '2 seconds' WHERE id=?",id);
        var replay=queue.claim("pod-b",1,Duration.ofSeconds(30),4).getFirst();
        sink.deliver(replay);
        assertThat(queue.ack(replay)).isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM order_event_projection_inbox WHERE event_id=?",Integer.class,id))
            .isEqualTo(1);
    }
    @Test void transientErrorsBackoffAndPoisonEndsDeadLetter() {
        var id=event(UUID.randomUUID(),"ORDER_CONFIRMED");
        var first=queue.claim("pod-a",1,Duration.ofSeconds(30),2).getFirst();
        assertThat(queue.fail(first,"temporary sink failure",Duration.ofSeconds(1),2)).isTrue();
        assertThat(queue.claim("pod-b",1,Duration.ofSeconds(30),2)).isEmpty();
        jdbc.update("UPDATE order_outbox_event SET next_attempt_at=now()-interval '1 second' WHERE id=?",id);
        var second=queue.claim("pod-b",1,Duration.ofSeconds(30),2).getFirst();
        assertThat(queue.fail(second,"poison",Duration.ofSeconds(1),2)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM order_outbox_event WHERE id=?",String.class,id))
            .isEqualTo("DEAD_LETTER");
        assertThat(queue.claim("pod-c",1,Duration.ofSeconds(30),2)).isEmpty();
    }
    @Test void aggregateEventsAreClaimedInCausalDatabaseInsertionOrder() {
        UUID order=UUID.randomUUID();
        var first=event(order,"ORDER_CONFIRMED");
        var second=additionalEvent(order,"ORDER_CANCELLED_BY_SELLER");
        var a=queue.claim("pod-a",10,Duration.ofSeconds(30),3);
        assertThat(a).extracting(OutboxClaim::id).containsExactly(first);
        assertThat(queue.ack(a.getFirst())).isTrue();
        var b=queue.claim("pod-b",10,Duration.ofSeconds(30),3);
        assertThat(b).extracting(OutboxClaim::id).containsExactly(second);
    }
    @Test void crashOnFinalAttemptBecomesDeadLetterWithoutStrandingLease() {
        UUID id=event(UUID.randomUUID(),"ORDER_CONFIRMED");
        var claim=queue.claim("pod-crashed",1,Duration.ofSeconds(30),1).getFirst();
        jdbc.update("UPDATE order_outbox_event SET lease_until=now()-interval '2 seconds' WHERE id=?",id);
        assertThat(queue.claim("another-pod",1,Duration.ofSeconds(30),1)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM order_outbox_event WHERE id=?",
            String.class,id)).isEqualTo("DEAD_LETTER");
        assertThat(queue.ack(claim)).isFalse();
    }
    @Test void dispatcherSurvivesSinkFailureAndRetriesWithIdempotentProjection() {
        UUID id=event(UUID.randomUUID(),"ORDER_CONFIRMED");
        var failures=new java.util.concurrent.atomic.AtomicInteger(0);
        var realSink=new JdbcOrderEventProjectionSink(jdbc);
        OutboxDeliverySink flaky=claim->{
            if(failures.getAndIncrement()==0) throw new IllegalStateException("Temporary failure");
            realSink.deliver(claim);
        };
        var dispatcher=new OutboxDispatcher(queue,flaky,"pod-dispatcher",3,3,Duration.ofSeconds(30));
        assertThat(dispatcher.pollOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM order_outbox_event WHERE id=?",
            String.class,id)).isEqualTo("PENDING");
        jdbc.update("UPDATE order_outbox_event SET next_attempt_at=now()-interval '1 second' WHERE id=?",id);
        assertThat(dispatcher.pollOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM order_outbox_event WHERE id=?",
            String.class,id)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM order_event_projection_inbox WHERE event_id=?",Integer.class,id))
            .isEqualTo(1);
    }

    @Test void healthCountsPendingInFlightDeadAndPublishedRows() {
        var first=event(UUID.randomUUID(),"ORDER_CONFIRMED");
        var second=event(UUID.randomUUID(),"ORDER_CONFIRMED");
        var third=event(UUID.randomUUID(),"ORDER_CONFIRMED");
        var claimed=queue.claim("health-pod",3,Duration.ofSeconds(30),3);
        assertThat(claimed).hasSize(3);
        assertThat(queue.ack(claimed.getFirst())).isTrue();
        assertThat(queue.fail(claimed.get(1),"poison",Duration.ZERO,1)).isTrue();
        var metrics=queue.health();
        assertThat(metrics.published()).isEqualTo(1);
        assertThat(metrics.deadLetter()).isEqualTo(1);
        assertThat(metrics.inFlight()).isEqualTo(1);
        assertThat(metrics.pending()).isZero();
        assertThat(metrics.oldestPendingSeconds()).isGreaterThanOrEqualTo(0);
    }
}
