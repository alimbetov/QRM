package kz.zakaz.intelligence.events;

import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Bounded internal candidate discovery for consented BuyerDemand.
 * Selects only sellers capable of fulfilling the entire basket from one
 * active location with free tracked stock. Does not message sellers.
 *
 * Existing evaluator and ML transactional event queue remain source of truth.
 */
@Service
@ConditionalOnProperty(name="zakaz.ml.r2.invitation-evaluation.enabled",
                       havingValue="true")
public class MlInvitationCandidateScanner {
    private final JdbcTemplate jdbc;
    private final MlSellerInvitationService evaluator;

    public record Candidate(UUID demandId,UUID sellerId) {}
    public MlInvitationCandidateScanner(JdbcTemplate jdbc,
            MlSellerInvitationService evaluator) {
        this.jdbc=jdbc;
        this.evaluator=evaluator;
    }

    /** Safe to invoke on multiple nodes; uniqueness is enforced by PostgreSQL. */
    public int scanOnce(int limit) {
        if(limit<1||limit>100)throw new IllegalArgumentException("Invalid candidate batch size");
        List<Candidate> matches=jdbc.query("""
            SELECT DISTINCT d.id AS demand_id,l.seller_id
            FROM buyer_demand_snapshot d
            JOIN ml_buyer_demand_invitation_consent buyer_consent
              ON buyer_consent.demand_id=d.id
             AND buyer_consent.buyer_id=d.buyer_id
             AND buyer_consent.enabled=true
            JOIN buyer_demand_line_snapshot request ON request.demand_id=d.id
            JOIN catalog_listing l ON l.product_id=request.product_id
              AND l.status='PUBLIC'
            JOIN ml_seller_invitation_preference seller_pref
              ON seller_pref.seller_id=l.seller_id
             AND seller_pref.in_app_enabled=true
            WHERE d.expires_at>clock_timestamp()
              AND NOT EXISTS (
                SELECT 1 FROM bid_acceptance accepted WHERE accepted.demand_id=d.id)
              AND NOT EXISTS (
                SELECT 1 FROM ml_seller_invitation previous
                WHERE previous.demand_id=d.id AND previous.seller_id=l.seller_id
                  AND previous.policy_version=1)
              AND EXISTS (
                SELECT 1 FROM seller_membership member
                WHERE member.seller_id=l.seller_id AND member.active=true
                  AND member.role='SELLER_ADMIN')
              AND """ + MlSellerEligibilityPolicy.stockedOrigin(
                "l.seller_id","d.id","d.city") + """
            ORDER BY demand_id,l.seller_id
            LIMIT ?
            """,(rs,i)->new Candidate(rs.getObject("demand_id",UUID.class),
                                       rs.getObject("seller_id",UUID.class)),limit);
        int created=0;
        for(Candidate candidate:matches) {
            var decision=evaluator.evaluate(candidate.demandId(),
                candidate.sellerId(),1,Instant.now());
            if("ELIGIBLE".equals(decision.status()))created++;
        }
        return created;
    }
}
