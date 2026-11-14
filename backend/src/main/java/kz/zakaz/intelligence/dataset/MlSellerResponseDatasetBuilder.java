package kz.zakaz.intelligence.dataset;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ML-R3.1 research-only, as-of-cutoff seller_response_24h observations.
 *
 * Positive: an actual immutable FIRST bid attributed to THIS invitation,
 * a valid quote snapshot and a committed ML trade event before cutoff.
 * Negative: an authenticated post-monitor-epoch exposure, a mature 24h
 * window, no bid, consent change, acceptance, competing exposure or any
 * monitored eligibility mutation throughout the full window.
 *
 * Censor all unknown histories, never guess a 0. Models remain disabled;
 * a research dataset is NOT evidence of predictive uplift.
 */
@Component
public class MlSellerResponseDatasetBuilder {
    public static final String SCHEMA="seller_response_24h.v2";
    public enum Label { POSITIVE, NEGATIVE, CENSORED }

    public record Sample(UUID invitationId,UUID demandId,UUID sellerId,
                         Instant viewedAt,int requestedLines,
                         Label label,String reason) {
        public Sample {
            Objects.requireNonNull(invitationId);Objects.requireNonNull(demandId);
            Objects.requireNonNull(sellerId);Objects.requireNonNull(viewedAt);
            Objects.requireNonNull(label);Objects.requireNonNull(reason);
            if(requestedLines<1||requestedLines>50)
                throw new IllegalArgumentException("Invalid requested lines");
        }
    }
    public record Dataset(Instant cutoff,String schemaVersion,String sha256,
                          List<Sample> samples,int positives,int negatives,
                          int censored,boolean trainable,String gate) {
        public Dataset {samples=List.copyOf(samples);}
    }
    private record Evidence(UUID invitationId,UUID demandId,UUID sellerId,
                            Instant viewedAt,Instant recordedAt,int lines,
                            Instant demandExpires,Instant monitoredSince,
                            Instant attributedFirstBid,boolean consentRevoked,
                            boolean priorBid,boolean otherInvitation,
                            boolean unaccountedBid,boolean demandAccepted,
                            boolean eligibilityChanged) {}

    private final JdbcTemplate jdbc;
    public MlSellerResponseDatasetBuilder(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Dataset build(Instant cutoff) {
        Objects.requireNonNull(cutoff);
        if(cutoff.isAfter(Instant.now()))
            throw new IllegalArgumentException("Future dataset cutoff");

        // A single typed cutoff placeholder is reused by the entire SQL
        // snapshot. No feature joins to future stock, seller metrics or price.
        // All mutation/event read paths are bounded by this same source cutoff.
        String query="""
            WITH params AS (SELECT CAST(? AS timestamptz) AS cutoff)
            SELECT v.invitation_id,v.demand_id,v.seller_id,v.viewed_at,
                   v.recorded_at,v.requested_lines,d.expires_at,
                   monitor.started_at AS monitored_since,
                   response.first_at,
                   EXISTS (
                     SELECT 1 FROM ml_invitation_consent_audit consent
                     WHERE consent.enabled=false
                       AND consent.recorded_at>=v.viewed_at
                       AND consent.recorded_at<=LEAST(
                         COALESCE(response.first_at,
                                  v.viewed_at + interval '24 hours'),p.cutoff)
                       AND ((consent.scope='DEMAND' AND consent.object_id=v.demand_id)
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
                       AND previous_event.ingested_at<=p.cutoff
                   ) AS previous_valid_bid,
                   EXISTS (
                     SELECT 1 FROM ml_seller_invitation_view competing
                     WHERE competing.demand_id=v.demand_id
                       AND competing.seller_id=v.seller_id
                       AND competing.invitation_id<>v.invitation_id
                       AND competing.viewed_at>=v.viewed_at
                       AND competing.viewed_at<=v.viewed_at + interval '24 hours'
                       AND competing.recorded_at<=p.cutoff
                   ) AS another_exposure,
                   EXISTS (
                     SELECT 1 FROM seller_bid_snapshot b2
                     JOIN bid_quote_snapshot q2 ON q2.bid_id=b2.id
                     JOIN ml_trade_event e2 ON e2.aggregate_type='BID'
                       AND e2.aggregate_id=b2.id AND e2.demand_id=v.demand_id
                       AND e2.seller_id=v.seller_id
                       AND e2.event_type IN ('BID_SUBMITTED','BID_REVISED')
                     WHERE b2.demand_id=v.demand_id AND b2.seller_id=v.seller_id
                       AND b2.submitted_at>=v.viewed_at
                       AND b2.submitted_at<=v.viewed_at + interval '24 hours'
                       AND b2.submitted_at<=p.cutoff
                       AND e2.ingested_at<=p.cutoff AND e2.occurred_at<=p.cutoff
                       AND NOT EXISTS (
                         SELECT 1 FROM ml_invitation_bid_attribution a2
                         WHERE a2.bid_id=b2.id AND a2.invitation_id=v.invitation_id
                           AND a2.recorded_at<=p.cutoff)
                   ) AS unattributed_bid,
                   EXISTS (
                     SELECT 1 FROM ml_trade_event acceptance
                     WHERE acceptance.demand_id=v.demand_id
                       AND acceptance.event_type='BID_ACCEPTED'
                       AND acceptance.occurred_at>=v.viewed_at
                       AND acceptance.occurred_at<=v.viewed_at + interval '24 hours'
                       AND acceptance.ingested_at<=p.cutoff
                   ) AS accepted_within_window,
                   EXISTS (
                     SELECT 1 FROM ml_r31_eligibility_change change_event
                     WHERE (change_event.seller_id=v.seller_id
                             OR change_event.seller_id IS NULL)
                       AND change_event.recorded_at>=v.viewed_at
                       AND change_event.recorded_at<=v.viewed_at + interval '24 hours'
                       AND change_event.recorded_at<=p.cutoff
                   ) AS eligibility_changed
            FROM ml_seller_invitation_view v
            JOIN ml_seller_invitation_inbox inbox ON
              inbox.invitation_id=v.invitation_id AND inbox.demand_id=v.demand_id
              AND inbox.seller_id=v.seller_id
            JOIN buyer_demand_snapshot d ON d.id=v.demand_id
            CROSS JOIN ml_r31_monitor_epoch monitor
            CROSS JOIN params p
            LEFT JOIN LATERAL (
              SELECT MIN(b.submitted_at) AS first_at
              FROM ml_invitation_bid_attribution ab
              JOIN seller_bid_snapshot b ON b.id=ab.bid_id
              JOIN bid_quote_snapshot q ON q.bid_id=b.id
              JOIN ml_trade_event e ON e.aggregate_type='BID'
                AND e.aggregate_id=b.id AND e.demand_id=v.demand_id
                AND e.seller_id=v.seller_id
                AND e.event_type='BID_SUBMITTED'
              WHERE ab.invitation_id=v.invitation_id
                AND ab.seller_id=v.seller_id AND ab.demand_id=v.demand_id
                AND ab.recorded_at<=p.cutoff
                AND b.revision=1
                AND b.submitted_at>=v.viewed_at
                AND b.submitted_at<=v.viewed_at + interval '24 hours'
                AND b.submitted_at<d.expires_at
                AND b.submitted_at<=p.cutoff
                AND e.occurred_at<=p.cutoff AND e.ingested_at<=p.cutoff
            ) response ON true
            WHERE v.viewed_at<=p.cutoff AND v.recorded_at<=p.cutoff
              AND inbox.delivered_at<=v.viewed_at
            """;
        var evidence=jdbc.query(query,(rs,i)->new Evidence(
            rs.getObject("invitation_id",UUID.class),
            rs.getObject("demand_id",UUID.class),
            rs.getObject("seller_id",UUID.class),
            rs.getTimestamp("viewed_at").toInstant(),
            rs.getTimestamp("recorded_at").toInstant(),
            rs.getInt("requested_lines"),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("monitored_since").toInstant(),
            rs.getTimestamp("first_at")==null?null:
                rs.getTimestamp("first_at").toInstant(),
            rs.getBoolean("consent_revoked"),
            rs.getBoolean("previous_valid_bid"),
            rs.getBoolean("another_exposure"),
            rs.getBoolean("unattributed_bid"),
            rs.getBoolean("accepted_within_window"),
            rs.getBoolean("eligibility_changed")),
            Timestamp.from(cutoff));

        // One independent observation for each demand/seller. An additional
        // viewed policy revision is NOT an extra training target.
        evidence.sort(Comparator.comparing(Evidence::viewedAt)
            .thenComparing(e->e.invitationId().toString()));
        var uniquePairs=new HashSet<String>();
        var rows=new ArrayList<Sample>();
        int positives=0,negatives=0;
        for(var e:evidence) {
            if(!uniquePairs.add(e.demandId()+"|"+e.sellerId()))continue;
            Instant horizon=e.viewedAt().plus(Duration.ofHours(24));
            Label label=Label.CENSORED;
            String reason;
            if(e.consentRevoked()) {
                reason="CONSENT_REVOKED";
            } else if(e.priorBid()) {
                reason="PRIOR_VALID_BID_BEFORE_VIEW";
            } else if(e.attributedFirstBid()!=null) {
                label=Label.POSITIVE;reason="VALID_BID_WITHIN_24H";positives++;
            } else if(e.otherInvitation()) {
                reason="MULTIPLE_EXPOSURES_IN_WINDOW";
            } else if(e.unaccountedBid()) {
                reason="UNATTRIBUTED_BID_IN_WINDOW";
            } else if(cutoff.isBefore(horizon)) {
                reason="INCOMPLETE_24H_HORIZON";
            } else if(e.demandExpires().isBefore(horizon)) {
                reason="DEMAND_EXPIRES_EARLY";
            } else if(e.demandAccepted()) {
                reason="DEMAND_ACCEPTED_IN_WINDOW";
            } else if(e.viewedAt().isBefore(e.monitoredSince())
                    || e.recordedAt().isBefore(e.monitoredSince())) {
                reason="PRE_MONITOR_EXPOSURE";
            } else if(e.eligibilityChanged()) {
                reason="ELIGIBILITY_CHANGED_IN_WINDOW";
            } else {
                label=Label.NEGATIVE;reason="VERIFIED_NO_RESPONSE_24H";negatives++;
            }
            rows.add(new Sample(e.invitationId(),e.demandId(),e.sellerId(),
                e.viewedAt(),e.lines(),label,reason));
        }
        rows.sort(Comparator.comparing(x->x.invitationId().toString()));
        StringBuilder canonical=new StringBuilder();
        canonical.append(SCHEMA).append('|').append(cutoff).append('\n');
        for(var e:rows) {
            canonical.append(e.invitationId()).append('|')
                .append(e.demandId()).append('|').append(e.sellerId()).append('|')
                .append(e.viewedAt()).append('|').append(e.requestedLines())
                .append('|').append(e.label()).append('|').append(e.reason()).append('\n');
        }
        return new Dataset(cutoff,SCHEMA,sha256(canonical.toString()),
            List.copyOf(rows),positives,negatives,rows.size()-positives-negatives,
            false,negatives==0?"BLOCKED_NO_VERIFIED_NEGATIVE_LABELS":
                "RESEARCH_ONLY_REQUIRES_REAL_HOLDOUT_AND_S2");
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
