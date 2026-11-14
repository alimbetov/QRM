package kz.zakaz.intelligence.events;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ML-R2 explicit request-sharing and merchant opt-in policy.
 * Missing preferences DENY; existing inbox items are hidden immediately
 * when consent or live visibility is revoked. External Push is unsupported.
 */
@Service
public class MlInvitationConsentService {
    public record InboxItem(UUID invitationId,UUID demandId,
                            java.time.Instant deliveredAt) {}
    private final JdbcTemplate jdbc;
    public MlInvitationConsentService(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    @Transactional
    public void setDemandSharing(UUID verifiedBuyer,UUID demandId,boolean enabled) {
        Objects.requireNonNull(verifiedBuyer);
        Objects.requireNonNull(demandId);
        Integer owned=jdbc.queryForObject("""
            SELECT count(*) FROM buyer_demand_snapshot WHERE id=? AND buyer_id=?
            """,Integer.class,demandId,verifiedBuyer);
        if(owned==null||owned!=1)
            throw new AccessDeniedException("Buyer does not own demand");
        if(enabled) {
            Boolean active=jdbc.queryForObject("""
                SELECT expires_at>clock_timestamp() AND NOT EXISTS(
                    SELECT 1 FROM bid_acceptance WHERE demand_id=?)
                FROM buyer_demand_snapshot WHERE id=?
                """,Boolean.class,demandId,demandId);
            if(!Boolean.TRUE.equals(active))
                throw new IllegalStateException("Cannot share expired or accepted demand");
        }
        jdbc.update("""
            INSERT INTO ml_buyer_demand_invitation_consent(
                demand_id,buyer_id,enabled,updated_at)
            VALUES (?,?,?,clock_timestamp())
            ON CONFLICT(demand_id) DO UPDATE
              SET enabled=EXCLUDED.enabled,updated_at=clock_timestamp()
            WHERE ml_buyer_demand_invitation_consent.buyer_id=EXCLUDED.buyer_id
            """,demandId,verifiedBuyer,enabled);
    }

    @Transactional
    public void setSellerInApp(UUID verifiedSeller,boolean enabled) {
        Objects.requireNonNull(verifiedSeller);
        jdbc.update("""
            INSERT INTO ml_seller_invitation_preference(
                seller_id,in_app_enabled,updated_at)
            VALUES (?,?,clock_timestamp())
            ON CONFLICT(seller_id) DO UPDATE
                SET in_app_enabled=EXCLUDED.in_app_enabled,
                    updated_at=clock_timestamp()
            """,verifiedSeller,enabled);
    }


    /** Missing consent/pref is false; verified buyer ownership is mandatory. */
    @Transactional(readOnly=true)
    public boolean demandSharingEnabled(UUID verifiedBuyer,UUID demandId) {
        Objects.requireNonNull(verifiedBuyer);
        Objects.requireNonNull(demandId);
        Integer owner=jdbc.queryForObject("""
            SELECT count(*) FROM buyer_demand_snapshot WHERE id=? AND buyer_id=?
            """,Integer.class,demandId,verifiedBuyer);
        if(owner==null||owner!=1)
            throw new AccessDeniedException("Buyer does not own demand");
        var values=jdbc.query("""
            SELECT enabled FROM ml_buyer_demand_invitation_consent
            WHERE demand_id=? AND buyer_id=?
            """,(rs,row)->rs.getBoolean(1),demandId,verifiedBuyer);
        return !values.isEmpty()&&values.getFirst();
    }

    @Transactional(readOnly=true)
    public boolean sellerInAppEnabled(UUID verifiedSeller) {
        Objects.requireNonNull(verifiedSeller);
        var values=jdbc.query("""
            SELECT in_app_enabled FROM ml_seller_invitation_preference
            WHERE seller_id=?
            """,(rs,row)->rs.getBoolean(1),verifiedSeller);
        return !values.isEmpty()&&values.getFirst();
    }

    @Transactional(readOnly=true)
    public List<InboxItem> listSellerInbox(UUID verifiedSeller,int limit) {
        Objects.requireNonNull(verifiedSeller);
        if(limit<1||limit>100)throw new IllegalArgumentException("Invalid inbox limit");
        return jdbc.query("""
            SELECT inbox.invitation_id,inbox.demand_id,inbox.delivered_at
            FROM ml_seller_invitation_inbox inbox
            JOIN buyer_demand_snapshot d ON d.id=inbox.demand_id
            JOIN ml_buyer_demand_invitation_consent c
              ON c.demand_id=d.id AND c.buyer_id=d.buyer_id AND c.enabled=true
            JOIN ml_seller_invitation_preference p
              ON p.seller_id=inbox.seller_id AND p.in_app_enabled=true
            WHERE inbox.seller_id=? AND d.expires_at>clock_timestamp()
              AND NOT EXISTS(SELECT 1 FROM bid_acceptance a WHERE a.demand_id=d.id)
              AND EXISTS(SELECT 1 FROM seller_membership m
                         WHERE m.seller_id=inbox.seller_id AND m.active=true
                           AND m.role IN ('SELLER_ADMIN','CATALOG_EDITOR'))
              AND EXISTS (
                SELECT 1 FROM catalog_listing l
                JOIN catalog_product cp ON cp.id=l.product_id
                JOIN catalog_category cat ON cat.id=cp.category_id
                JOIN catalog_sku sku ON sku.listing_id=l.id
                JOIN buyer_demand_line_snapshot line ON line.product_id=cp.id
                WHERE l.seller_id=inbox.seller_id AND l.status='PUBLIC'
                  AND cat.active=true AND line.demand_id=d.id
                  AND sku.tracking_mode='TRACKED'
                  AND sku.sales_unit=line.unit AND line.unit IS NOT NULL)
              AND (
                  EXISTS(SELECT 1 FROM seller_delivery_area a
                         WHERE a.seller_id=inbox.seller_id AND a.city=d.city
                           AND a.active=true)
                  OR EXISTS(SELECT 1 FROM inventory_location l
                         WHERE l.seller_id=inbox.seller_id AND l.city=d.city
                           AND l.active=true AND l.public_pickup_enabled=true)
              )
            ORDER BY inbox.delivered_at DESC,inbox.invitation_id DESC LIMIT ?
            """,(rs,i)->new InboxItem(rs.getObject("invitation_id",UUID.class),
                    rs.getObject("demand_id",UUID.class),
                    rs.getTimestamp("delivered_at").toInstant()),
            verifiedSeller,limit);
    }
}
