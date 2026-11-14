package kz.zakaz.intelligence.events;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ML-R2 internal consumer. Transactional inbox + projection commit atomically.
 * ACK occurs only afterwards in dispatcher. No network calls, email or push.
 */
@Component
public class JdbcMlEventProjectionSink {
    private static final String CONSUMER_ID="ml-r2-projection-v1";
    private final JdbcTemplate jdbc;
    private record Event(UUID id,UUID demandId,UUID aggregateId,String aggregateType,
                         String type,int revision,int schemaVersion,UUID sellerId,
                         Instant occurredAt,Instant ingestedAt){}

    public JdbcMlEventProjectionSink(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    @Transactional
    public void deliver(JdbcMlEventQueue.Claim claim) {
        Objects.requireNonNull(claim);
        Event event=jdbc.queryForObject("""
            SELECT event_id,demand_id,aggregate_id,aggregate_type,event_type,
                   source_revision,schema_version,seller_id,occurred_at,ingested_at
            FROM ml_trade_event WHERE event_id=?
            """,(rs,i)->new Event(
                rs.getObject("event_id",UUID.class),
                rs.getObject("demand_id",UUID.class),
                rs.getObject("aggregate_id",UUID.class),
                rs.getString("aggregate_type"),
                rs.getString("event_type"),
                rs.getInt("source_revision"),
                rs.getInt("schema_version"),
                rs.getObject("seller_id",UUID.class),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("ingested_at").toInstant()),claim.eventId());
        if(event==null||!event.demandId().equals(claim.demandId())||
            event.schemaVersion()!=1)
            throw new IllegalStateException("Unknown ML event or schema mismatch");

        String fingerprint=sha256(event);
        int applied=jdbc.update("""
            INSERT INTO ml_event_projection_inbox(consumer_id,event_id,fingerprint)
            VALUES (?,?,?) ON CONFLICT(consumer_id,event_id) DO NOTHING
            """,CONSUMER_ID,event.id(),fingerprint);
        if(applied==0) {
            String recorded=jdbc.queryForObject("""
                SELECT fingerprint FROM ml_event_projection_inbox
                WHERE consumer_id=? AND event_id=?
                """,String.class,CONSUMER_ID,event.id());
            if(!fingerprint.equals(recorded))
                throw new IllegalStateException("Conflicting replay fingerprint");
            return; // after-sink-before-ACK crash is a harmless replay
        }

        if("SELLER_INVITATION_EVALUATED".equals(event.type())) {
            // Consent is checked at the moment of delivery, not at candidate
            // scoring time. Missing/revoked consent is DENY. A new policy
            // version is required to re-evaluate after any policy change.
            jdbc.update("""
                INSERT INTO ml_seller_invitation_inbox(invitation_id,demand_id,seller_id)
                SELECT i.id,i.demand_id,i.seller_id
                  FROM ml_seller_invitation i
                  JOIN buyer_demand_snapshot d ON d.id=i.demand_id
                  JOIN ml_buyer_demand_invitation_consent b
                    ON b.demand_id=d.id AND b.buyer_id=d.buyer_id
                      AND b.enabled=true
                  JOIN ml_seller_invitation_preference p
                    ON p.seller_id=i.seller_id AND p.in_app_enabled=true
                 WHERE i.id=? AND i.status='ELIGIBLE' AND i.reason_code='MATCHED'
                   AND d.expires_at>clock_timestamp()
                   AND NOT EXISTS(SELECT 1 FROM bid_acceptance a WHERE a.demand_id=d.id)
                   AND EXISTS (
                     SELECT 1 FROM seller_membership m
                     WHERE m.seller_id=i.seller_id AND m.active=true
                       AND m.role='SELLER_ADMIN')
                   AND """ + MlSellerEligibilityPolicy.stockedOrigin(
                     "i.seller_id","d.id","d.city") + """
                ON CONFLICT(invitation_id) DO NOTHING
                """,event.aggregateId());
        }
    }

    private static String sha256(Event e) {
        String raw=String.join("|",
            e.id().toString(),e.demandId().toString(),e.aggregateId().toString(),
            e.aggregateType(),e.type(),Integer.toString(e.revision()),
            Integer.toString(e.schemaVersion()),String.valueOf(e.sellerId()),
            e.occurredAt().toString(),e.ingestedAt().toString());
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }
}
