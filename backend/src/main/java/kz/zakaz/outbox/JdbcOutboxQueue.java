package kz.zakaz.outbox;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cross-pod PostgreSQL coordination, not in-memory ownership.
 * Claims commit before any external sink call; a stale lease cannot ACK/heartbeat.
 */
@Repository
public class JdbcOutboxQueue {
    private final JdbcTemplate jdbc;
    public JdbcOutboxQueue(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @Transactional
    public List<OutboxClaim> claim(String workerId,int batchSize,Duration lease,int maxAttempts){
        if(workerId==null||workerId.isBlank()||workerId.length()>120
            ||batchSize<1||batchSize>100||maxAttempts<1
            ||lease==null||lease.toMillis()<100||lease.toMillis()>300000)
            throw new IllegalArgumentException("Invalid outbox worker bounds");
        // Expired last-attempt claims are terminal, never stuck IN_FLIGHT forever.
        jdbc.update("""
            UPDATE order_outbox_event SET status='DEAD_LETTER',
                   lease_owner=NULL,lease_token=NULL,lease_until=NULL,
                   last_error=COALESCE(last_error,'Lease expired after last attempt')
            WHERE status='IN_FLIGHT' AND lease_until<=clock_timestamp() AND attempt_count>=?
            """,maxAttempts);
        UUID token=UUID.randomUUID();
        var items=jdbc.query("""
            WITH candidates AS (
                SELECT e.id
                  FROM order_outbox_event e
                 WHERE ((e.status='PENDING' AND e.next_attempt_at<=clock_timestamp())
                     OR (e.status='IN_FLIGHT' AND e.lease_until<=clock_timestamp()))
                   AND e.attempt_count < ?
                   AND NOT EXISTS (
                      SELECT 1 FROM order_outbox_event earlier
                       WHERE earlier.aggregate_id=e.aggregate_id
                         AND earlier.event_sequence<e.event_sequence
                         AND earlier.status <> 'PUBLISHED'
                   )
                 ORDER BY e.event_sequence
                 FOR UPDATE OF e SKIP LOCKED
                 LIMIT ?
            )
            UPDATE order_outbox_event e
               SET status='IN_FLIGHT',lease_owner=?,lease_token=?,
                   lease_until=clock_timestamp()+(? * INTERVAL '1 millisecond'),
                   claim_generation=e.claim_generation+1,
                   attempt_count=e.attempt_count+1
              FROM candidates c WHERE e.id=c.id
            RETURNING e.id,e.aggregate_id,e.event_type,e.payload,e.event_sequence,
                      e.claim_generation,e.lease_token,e.attempt_count
            """,(rs,i)->new OutboxClaim(
                rs.getObject("id",UUID.class),
                rs.getObject("aggregate_id",UUID.class),
                rs.getString("event_type"),rs.getString("payload"),
                rs.getLong("event_sequence"),rs.getLong("claim_generation"),
                rs.getObject("lease_token",UUID.class),rs.getInt("attempt_count")),
                maxAttempts,batchSize,workerId,token,lease.toMillis());
        items.sort(Comparator.comparingLong(OutboxClaim::sequence));
        return List.copyOf(items);
    }
    @Transactional
    public boolean ack(OutboxClaim claim){
        Objects.requireNonNull(claim);
        return jdbc.update("""
            UPDATE order_outbox_event SET status='PUBLISHED',
                published_at=clock_timestamp(),
                lease_owner=NULL,lease_token=NULL,lease_until=NULL,last_error=NULL
            WHERE id=? AND lease_token=? AND claim_generation=?
              AND status='IN_FLIGHT' AND lease_until>clock_timestamp()
            """,claim.id(),claim.token(),claim.generation())==1;
    }
    @Transactional
    public boolean extend(OutboxClaim claim,Duration extendBy){
        Objects.requireNonNull(claim);
        if(extendBy==null||extendBy.toMillis()<100||extendBy.toMillis()>300000)
            throw new IllegalArgumentException("Invalid heartbeat lease");
        return jdbc.update("""
            UPDATE order_outbox_event SET lease_until=clock_timestamp()+(? * INTERVAL '1 millisecond')
            WHERE id=? AND lease_token=? AND claim_generation=?
              AND status='IN_FLIGHT' AND lease_until>clock_timestamp()
            """,extendBy.toMillis(),claim.id(),claim.token(),claim.generation())==1;
    }
    @Transactional
    public boolean fail(OutboxClaim claim,String error,Duration retryDelay,int maxAttempts){
        Objects.requireNonNull(claim);
        if(retryDelay==null||retryDelay.isNegative()||retryDelay.toMillis()>3600000
            ||maxAttempts<1)throw new IllegalArgumentException("Invalid retry configuration");
        String reason=error==null?"Unknown sink failure":error.substring(0,Math.min(error.length(),500));
        String next=claim.attempts()>=maxAttempts?"DEAD_LETTER":"PENDING";
        return jdbc.update("""
            UPDATE order_outbox_event SET status=?,last_error=?,
                next_attempt_at=clock_timestamp()+(? * INTERVAL '1 millisecond'),
                lease_owner=NULL,lease_token=NULL,lease_until=NULL
            WHERE id=? AND lease_token=? AND claim_generation=?
                AND status='IN_FLIGHT' AND lease_until>clock_timestamp()
            """,next,reason,retryDelay.toMillis(),claim.id(),claim.token(),claim.generation())==1;
    }
}
