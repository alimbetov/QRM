package kz.zakaz.outbox;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Concrete INTERNAL sink, not an external webhook or broker.
 * Unique inbox key makes replay after delivery-before-ACK harmless.
 */
public final class JdbcOrderEventProjectionSink implements OutboxDeliverySink {
    private final JdbcTemplate jdbc;
    public JdbcOrderEventProjectionSink(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    @Override
    public void deliver(OutboxClaim claim){
        Objects.requireNonNull(claim);
        jdbc.update("""
            INSERT INTO order_event_projection_inbox(
                consumer_id,event_id,aggregate_id,event_type,payload)
            VALUES ('order-projection-v1',?,?,?,?)
            ON CONFLICT (consumer_id,event_id) DO NOTHING
            """,claim.id(),claim.aggregateId(),claim.eventType(),claim.payload());
    }
}
