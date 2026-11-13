package kz.zakaz.intelligence.events;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ML-R2: persist ONE historical seller-invitation ELIGIBILITY decision per
 * demand/seller/policy. This never sends email, push, or a seller invitation.
 *
 * The seller ID must originate from a trusted server-side candidate finder.
 * All eligibility checks use authoritative PostgreSQL tables at evaluation.
 * Actual messaging requires separate consent, seller preferences and a
 * general-purpose notification outbox (not order_outbox_event).
 */
@Service
@ConditionalOnProperty(name="zakaz.ml.r2.invitation-evaluation.enabled",havingValue="true")
public class MlSellerInvitationService {
    public record Decision(UUID id,UUID demandId,UUID sellerId,int policyVersion,
                           String status,String reasonCode,Instant evaluatedAt) {}
    private record DemandState(String city,Instant expiresAt) {}

    private final JdbcTemplate jdbc;

    public MlSellerInvitationService(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
    }

    @Transactional
    public Decision evaluate(UUID demandId,UUID trustedSellerId,
                             int policyVersion,Instant evaluatedAt) {
        Objects.requireNonNull(demandId);
        Objects.requireNonNull(trustedSellerId);
        Objects.requireNonNull(evaluatedAt);
        if(policyVersion<1||policyVersion>1_000_000)
            throw new IllegalArgumentException("Invalid invitation policy version");
        // Only a server-side evaluator calls this service; there is no public
        // invitation endpoint and no client-supplied ELIGIBLE flag.
        var found=jdbc.query("""
            SELECT city,expires_at FROM buyer_demand_snapshot WHERE id=?
            """,(rs,i)->new DemandState(rs.getString("city"),
                rs.getTimestamp("expires_at").toInstant()),demandId);
        if(found.isEmpty()) throw new NoSuchElementException("Buyer demand not found");
        var demand=found.getFirst();

        String reason;
        if(!evaluatedAt.isBefore(demand.expiresAt())) {
            reason="EXPIRED";
        } else if(exists("""
                SELECT EXISTS(SELECT 1 FROM bid_acceptance WHERE demand_id=?)
                """,demandId)) {
            reason="ALREADY_ACCEPTED";
        } else if(!exists("""
                SELECT EXISTS(SELECT 1 FROM seller_membership
                    WHERE seller_id=? AND active=true
                      AND role IN ('SELLER_ADMIN','CATALOG_EDITOR'))
                """,trustedSellerId)) {
            reason="NO_ACTIVE_MEMBERSHIP";
        } else if(!exists("""
                SELECT EXISTS(
                    SELECT 1 FROM catalog_listing l
                    JOIN catalog_product p ON p.id=l.product_id
                    JOIN catalog_category c ON c.id=p.category_id
                    JOIN catalog_sku sku ON sku.listing_id=l.id
                    JOIN buyer_demand_line_snapshot d ON d.product_id=p.id
                    WHERE l.seller_id=? AND l.status='PUBLIC' AND c.active=true
                      AND sku.tracking_mode='TRACKED'
                      AND sku.sales_unit=d.unit AND d.unit IS NOT NULL
                      AND d.demand_id=?
                )
                """,trustedSellerId,demandId)) {
            reason="NO_PUBLIC_COMPATIBLE_SKU";
        } else if(!exists("""
                SELECT EXISTS(
                    SELECT 1 FROM seller_delivery_area a
                    WHERE a.seller_id=? AND a.city=? AND a.active=true
                    UNION ALL
                    SELECT 1 FROM inventory_location l
                    WHERE l.seller_id=? AND l.city=? AND l.active=true
                      AND l.public_pickup_enabled=true
                )
                """,trustedSellerId,demand.city(),trustedSellerId,demand.city())) {
            reason="NO_FULFILLMENT_AREA";
        } else {
            reason="MATCHED";
        }

        String status=reason.equals("MATCHED")?"ELIGIBLE":"SUPPRESSED";
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO ml_seller_invitation(
                id,demand_id,seller_id,policy_version,status,reason_code,evaluated_at)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT(demand_id,seller_id,policy_version) DO NOTHING
            """,id,demandId,trustedSellerId,policyVersion,status,reason,
            Timestamp.from(evaluatedAt));

        // Database uniqueness is the source of truth under concurrency.
        // Replays return the FIRST immutable decision, never update it.
        return jdbc.queryForObject("""
            SELECT id,demand_id,seller_id,policy_version,status,
                   reason_code,evaluated_at
            FROM ml_seller_invitation
            WHERE demand_id=? AND seller_id=? AND policy_version=?
            """,(rs,i)->new Decision(rs.getObject("id",UUID.class),
                rs.getObject("demand_id",UUID.class),
                rs.getObject("seller_id",UUID.class),
                rs.getInt("policy_version"),rs.getString("status"),
                rs.getString("reason_code"),
                rs.getTimestamp("evaluated_at").toInstant()),
            demandId,trustedSellerId,policyVersion);
    }

    private boolean exists(String query,Object... params) {
        return Boolean.TRUE.equals(jdbc.queryForObject(query,Boolean.class,params));
    }
}
