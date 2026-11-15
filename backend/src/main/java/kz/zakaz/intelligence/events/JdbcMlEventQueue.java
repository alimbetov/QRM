package kz.zakaz.intelligence.events;

import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Independent ML-R2 PostgreSQL queue. A claim COMMITs before consumer work,
 * a stale worker cannot ACK, and a crashed worker is reclaimed after lease.
 * Events from the same demand are consumed in stored causal sequence.
 */
@Repository
public class JdbcMlEventQueue {
    public record Claim(UUID eventId,UUID demandId,long sequence,
                        long generation,UUID token,int attempts) {
        public Claim {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(demandId);
            Objects.requireNonNull(token);
            if(sequence<1||generation<1||attempts<1)
                throw new IllegalArgumentException("Invalid ML event claim");
        }
    }

    private final JdbcTemplate jdbc;
    public JdbcMlEventQueue(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @Transactional
    public List<Claim> claim(String worker,int limit,Duration lease,int maxAttempts) {
        if(worker==null||worker.isBlank()||worker.length()>120||
            limit<1||limit>100||maxAttempts<1||
            lease==null||lease.toMillis()<100||lease.toMillis()>300000)
            throw new IllegalArgumentException("Invalid ML event worker configuration");

        jdbc.update("""
            UPDATE ml_event_delivery SET status='DEAD_LETTER',
                lease_owner=NULL,lease_token=NULL,lease_until=NULL,
                last_error=COALESCE(last_error,'Final attempt lease expired')
            WHERE status='IN_FLIGHT' AND lease_until<=clock_timestamp()
              AND attempt_count>=?
            """,maxAttempts);

        UUID token=UUID.randomUUID();
        var claims=jdbc.query("""
            WITH candidates AS (
                SELECT d.event_id FROM ml_event_delivery d
                WHERE ((d.status='PENDING' AND d.next_attempt_at<=clock_timestamp())
                    OR (d.status='IN_FLIGHT' AND d.lease_until<=clock_timestamp()))
                  AND d.attempt_count<?
                  AND NOT EXISTS (
                      SELECT 1 FROM ml_event_delivery prior
                       WHERE prior.demand_id=d.demand_id
                         AND prior.event_sequence<d.event_sequence
                         AND prior.status<>'DELIVERED'
                  )
                ORDER BY d.event_sequence LIMIT ?
                FOR UPDATE OF d SKIP LOCKED
            )
            UPDATE ml_event_delivery d
               SET status='IN_FLIGHT',lease_owner=?,lease_token=?,
                   lease_until=clock_timestamp()+(? * interval '1 millisecond'),
                   claim_generation=d.claim_generation+1,
                   attempt_count=d.attempt_count+1
              FROM candidates c WHERE d.event_id=c.event_id
            RETURNING d.event_id,d.demand_id,d.event_sequence,
                      d.claim_generation,d.lease_token,d.attempt_count
            """,(rs,i)->new Claim(
                rs.getObject("event_id",UUID.class),
                rs.getObject("demand_id",UUID.class),
                rs.getLong("event_sequence"),
                rs.getLong("claim_generation"),
                rs.getObject("lease_token",UUID.class),
                rs.getInt("attempt_count")),
            maxAttempts,limit,worker,token,lease.toMillis());
        claims.sort(Comparator.comparingLong(Claim::sequence));
        return List.copyOf(claims);
    }

    @Transactional
    public boolean ack(Claim claim) {
        Objects.requireNonNull(claim);
        return jdbc.update("""
            UPDATE ml_event_delivery SET status='DELIVERED',
                delivered_at=clock_timestamp(),last_error=NULL,
                lease_owner=NULL,lease_token=NULL,lease_until=NULL
            WHERE event_id=? AND demand_id=? AND status='IN_FLIGHT'
                AND lease_token=? AND claim_generation=?
                AND lease_until>clock_timestamp()
            """,claim.eventId(),claim.demandId(),claim.token(),claim.generation())==1;
    }

    @Transactional
    public boolean extend(Claim claim,Duration extension) {
        Objects.requireNonNull(claim);
        if(extension==null||extension.toMillis()<100||extension.toMillis()>300000)
            throw new IllegalArgumentException("Invalid heartbeat");
        return jdbc.update("""
            UPDATE ml_event_delivery SET
                lease_until=clock_timestamp()+(? * interval '1 millisecond')
            WHERE event_id=? AND demand_id=? AND status='IN_FLIGHT'
                AND lease_token=? AND claim_generation=?
                AND lease_until>clock_timestamp()
            """,extension.toMillis(),claim.eventId(),claim.demandId(),
                claim.token(),claim.generation())==1;
    }

    @Transactional
    public boolean fail(Claim claim,String error,Duration delay,int maxAttempts) {
        Objects.requireNonNull(claim);
        if(delay==null||delay.isNegative()||delay.toMillis()>3600000||
           maxAttempts<1)throw new IllegalArgumentException("Invalid retry settings");
        String reason=error==null?"Unknown delivery failure":
            error.substring(0,Math.min(error.length(),200));
        String status=claim.attempts()>=maxAttempts?"DEAD_LETTER":"PENDING";
        return jdbc.update("""
            UPDATE ml_event_delivery SET status=?,last_error=?,
                next_attempt_at=clock_timestamp()+(? * interval '1 millisecond'),
                lease_owner=NULL,lease_token=NULL,lease_until=NULL
            WHERE event_id=? AND demand_id=? AND status='IN_FLIGHT'
                AND lease_token=? AND claim_generation=?
                AND lease_until>clock_timestamp()
            """,status,reason,delay.toMillis(),claim.eventId(),claim.demandId(),
                claim.token(),claim.generation())==1;
    }

    public Map<String,Long> health() {
        return jdbc.queryForObject("""
            SELECT count(*) FILTER(WHERE status='PENDING') pending,
                   count(*) FILTER(WHERE status='IN_FLIGHT') in_flight,
                   count(*) FILTER(WHERE status='DELIVERED') delivered,
                   count(*) FILTER(WHERE status='DEAD_LETTER') dead_letter
            FROM ml_event_delivery
            """,(rs,i)->Map.of(
                "pending",rs.getLong("pending"),
                "inFlight",rs.getLong("in_flight"),
                "delivered",rs.getLong("delivered"),
                "deadLetter",rs.getLong("dead_letter")));
    }
}
