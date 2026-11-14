package kz.zakaz.intelligence.dataset;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ML-R3 first point-in-time, deterministic seller_response_24h dataset.
 *
 * Data are from server-acknowledged invitation views, immutable bid quote
 * snapshots and transactionally captured events, not queue delivery or
 * seller-invitation eligibility. All temporal joins are bounded by cutoff.
 *
 * IMPORTANT: negative response labels are intentionally NOT produced until
 * continuous seller/catalog/stock eligibility throughout the 24h horizon
 * can be proven. Unknown is censored, never converted into a false zero.
 * This component does not train, score or activate a Tribuo model.
 */
@Component
public class MlSellerResponseDatasetBuilder {
    public static final String SCHEMA="seller_response_24h.v1";
    public enum Label { POSITIVE, CENSORED }
    public record Sample(UUID invitationId,UUID demandId,UUID sellerId,
                         Instant viewedAt,int requestedLines,
                         Label label,String reason) {
        public Sample {
            Objects.requireNonNull(invitationId);Objects.requireNonNull(demandId);
            Objects.requireNonNull(sellerId);Objects.requireNonNull(viewedAt);
            Objects.requireNonNull(label);Objects.requireNonNull(reason);
            if(requestedLines<1||requestedLines>50)throw new IllegalArgumentException("Invalid basket");
        }
    }
    public record Dataset(Instant cutoff,String schemaVersion,String sha256,
                          List<Sample> samples,int positives,int negatives,
                          int censored,boolean trainable,String gate) {
        public Dataset { samples=List.copyOf(samples); }
    }
    private record Evidence(UUID invitationId,UUID demandId,UUID sellerId,
                            Instant viewedAt,int lines,Instant demandExpires,
                            Instant firstValidBid,boolean consentRevoked,
                            boolean previousValidBid) {}

    private final JdbcTemplate jdbc;
    public MlSellerResponseDatasetBuilder(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Dataset build(Instant cutoff) {
        Objects.requireNonNull(cutoff);
        if(cutoff.isAfter(Instant.now()))
            throw new IllegalArgumentException("Future dataset cutoff");

        // No enrichment using current seller behavior, revenue, prices, or
        // present-day inventory: those would leak post-exposure information.
        // Bids require an actual immutable quote and a committed BID event
        // ingested no later than the requested source cutoff.
        String query="""
            SELECT v.invitation_id,v.demand_id,v.seller_id,
                   v.viewed_at,v.requested_lines,d.expires_at,
                   response.first_at,
                   EXISTS (
                     SELECT 1 FROM ml_invitation_consent_audit consent
                     WHERE consent.enabled=false
                       AND consent.recorded_at>=v.viewed_at
                       AND consent.recorded_at<=LEAST(
                         COALESCE(response.first_at,v.viewed_at + interval '24 hours'),?)
                       AND (
                         (consent.scope='DEMAND' AND consent.object_id=v.demand_id)
                         OR (consent.scope='SELLER' AND consent.object_id=v.seller_id))
                   ) AS consent_revoked,
                   EXISTS (
                     SELECT 1 FROM seller_bid_snapshot previous
                     JOIN bid_quote_snapshot previous_quote
                       ON previous_quote.bid_id=previous.id
                     JOIN ml_trade_event previous_event
                       ON previous_event.aggregate_type='BID'
                      AND previous_event.aggregate_id=previous.id
                      AND previous_event.demand_id=v.demand_id
                      AND previous_event.seller_id=v.seller_id
                      AND previous_event.event_type IN ('BID_SUBMITTED','BID_REVISED')
                     WHERE previous.demand_id=v.demand_id
                       AND previous.seller_id=v.seller_id
                       AND previous.submitted_at<v.viewed_at
                       AND previous_event.ingested_at<=?
                   ) AS previous_valid_bid
            FROM ml_seller_invitation_view v
            JOIN ml_seller_invitation_inbox inbox ON
              inbox.invitation_id=v.invitation_id AND inbox.demand_id=v.demand_id
              AND inbox.seller_id=v.seller_id
            JOIN buyer_demand_snapshot d ON d.id=v.demand_id
            LEFT JOIN LATERAL (
              SELECT MIN(b.submitted_at) AS first_at
              FROM seller_bid_snapshot b
              JOIN bid_quote_snapshot q ON q.bid_id=b.id
              JOIN ml_trade_event e ON e.aggregate_type='BID'
                AND e.aggregate_id=b.id AND e.demand_id=v.demand_id
                AND e.seller_id=v.seller_id
                AND e.event_type IN ('BID_SUBMITTED','BID_REVISED')
              WHERE b.demand_id=v.demand_id AND b.seller_id=v.seller_id
                AND b.submitted_at>=v.viewed_at
                AND b.submitted_at<=v.viewed_at + interval '24 hours'
                AND b.submitted_at<d.expires_at
                AND b.submitted_at<=? AND e.occurred_at<=?
                AND e.ingested_at<=?
            ) response ON true
            WHERE v.viewed_at<=? AND v.recorded_at<=?
              AND inbox.delivered_at<=v.viewed_at
            """;
        var evidence=jdbc.query(query,(rs,i)->new Evidence(
            rs.getObject("invitation_id",UUID.class),
            rs.getObject("demand_id",UUID.class),
            rs.getObject("seller_id",UUID.class),
            rs.getTimestamp("viewed_at").toInstant(),
            rs.getInt("requested_lines"),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("first_at")==null?null:
                rs.getTimestamp("first_at").toInstant(),
            rs.getBoolean("consent_revoked"),
            rs.getBoolean("previous_valid_bid")),
            java.sql.Timestamp.from(cutoff),java.sql.Timestamp.from(cutoff),
            java.sql.Timestamp.from(cutoff),java.sql.Timestamp.from(cutoff),
            java.sql.Timestamp.from(cutoff),java.sql.Timestamp.from(cutoff),
            java.sql.Timestamp.from(cutoff));
        // Immutable policy revisions may deliver several invitations to one
        // demand/seller pair. Use its first acknowledged exposure as the one
        // independent observation; never inflate cohort size on re-evaluation.
        evidence.sort(Comparator.comparing(Evidence::viewedAt)
            .thenComparing(e->e.invitationId().toString()));
        var uniquePairs=new HashSet<String>();
        var rows=new ArrayList<Sample>();
        int positives=0;
        for(var e:evidence) {
            if(!uniquePairs.add(e.demandId()+"|"+e.sellerId())) continue;
            Label label;
            String reason;
            Instant end=e.viewedAt().plus(Duration.ofHours(24));
            if(e.consentRevoked()) {
                label=Label.CENSORED;reason="CONSENT_REVOKED";
            } else if(e.previousValidBid()) {
                // A later revision of an already-existing bid is NOT
                // the seller's first response to this new exposure.
                label=Label.CENSORED;reason="PRIOR_VALID_BID_BEFORE_VIEW";
            } else if(e.firstValidBid()!=null) {
                label=Label.POSITIVE;reason="VALID_BID_WITHIN_24H";
                positives++;
            } else if(cutoff.isBefore(end)) {
                label=Label.CENSORED;reason="INCOMPLETE_24H_HORIZON";
            } else if(e.demandExpires().isBefore(end)) {
                label=Label.CENSORED;reason="DEMAND_EXPIRES_EARLY";
            } else {
                // Available stock, account status and public listing history
                // are not yet continuously audited for the entire 24h period.
                label=Label.CENSORED;reason="UNVERIFIED_CONTINUOUS_ELIGIBILITY";
            }
            rows.add(new Sample(e.invitationId(),e.demandId(),e.sellerId(),
                e.viewedAt(),e.lines(),label,reason));
        }
        rows.sort(Comparator.comparing(row->row.invitationId().toString()));
        StringBuilder canonical=new StringBuilder();
        canonical.append(SCHEMA).append('|').append(cutoff).append('\n');
        for(var row:rows) {
            canonical.append(row.invitationId()).append('|')
                .append(row.demandId()).append('|').append(row.sellerId()).append('|')
                .append(row.viewedAt()).append('|').append(row.requestedLines())
                .append('|').append(row.label()).append('|').append(row.reason()).append('\n');
        }
        int censored=rows.size()-positives;
        return new Dataset(cutoff,SCHEMA,sha256(canonical.toString()),
            List.copyOf(rows),positives,0,censored,
            false,"BLOCKED_NO_VERIFIED_NEGATIVE_LABELS");
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable",impossible);
        }
    }
}
