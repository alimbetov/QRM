package kz.zakaz.intelligence.events;

import java.time.Instant;
import java.util.*;
import kz.zakaz.deal.SellerInvitationBidWorkspaceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ML-R3 explicit authenticated seller view acknowledgement.
 * Only a real, still-visible delivered invitation and valid quote context
 * can be acknowledged; a queue/inbox delivery is never treated as a view.
 * The first view is immutable and idempotent across retries and workers.
 *
 * The server proves authenticated acknowledgement, NOT that a human eye
 * saw pixels. A seller UI must call POST only after presentation.
 */
@Service
public class MlInvitationViewService {
    private final JdbcTemplate jdbc;
    private final SellerInvitationBidWorkspaceService workspace;

    public MlInvitationViewService(JdbcTemplate jdbc,
                                   SellerInvitationBidWorkspaceService workspace) {
        this.jdbc=jdbc;this.workspace=workspace;
    }

    @Transactional
    public Instant acknowledge(UUID trustedSellerId, UUID invitationId) {
        Objects.requireNonNull(trustedSellerId);Objects.requireNonNull(invitationId);

        // Serialize with buyer consent / seller opt-out updates: the same
        // row locks are used by the existing seller bid workspace.
        var accessible=jdbc.query("""
            SELECT i.demand_id
            FROM ml_seller_invitation_inbox i
            JOIN ml_seller_invitation source ON source.id=i.invitation_id
              AND source.seller_id=i.seller_id AND source.demand_id=i.demand_id
              AND source.status='ELIGIBLE'
            JOIN buyer_demand_snapshot d ON d.id=i.demand_id
            JOIN ml_buyer_demand_invitation_consent c ON c.demand_id=d.id
              AND c.buyer_id=d.buyer_id AND c.enabled=true
            JOIN ml_seller_invitation_preference p ON p.seller_id=i.seller_id
              AND p.in_app_enabled=true
            WHERE i.invitation_id=? AND i.seller_id=?
              AND d.expires_at>clock_timestamp()
              AND NOT EXISTS (SELECT 1 FROM bid_acceptance a
                              WHERE a.demand_id=d.id)
            FOR SHARE OF c,p
            """,(rs,i)->rs.getObject("demand_id",UUID.class),
            invitationId,trustedSellerId);
        if(accessible.isEmpty())
            throw new NoSuchElementException("Invitation not available");

        // Uses the same authorization, full-basket, stocked-origin, consent
        // and public catalog rules as the actual seller quote workflow.
        var quote=workspace.preview(trustedSellerId,invitationId);
        if(!quote.demandId().equals(accessible.getFirst()))
            throw new IllegalStateException("Invitation lineage mismatch");
        int requestedLines=quote.lines().size();
        if(requestedLines<1||requestedLines>50)
            throw new IllegalStateException("Unsupported basket size");

        jdbc.update("""
            INSERT INTO ml_seller_invitation_view(
              invitation_id,demand_id,seller_id,requested_lines)
            VALUES (?,?,?,?) ON CONFLICT(invitation_id) DO NOTHING
            """,invitationId,quote.demandId(),trustedSellerId,requestedLines);
        var views=jdbc.query("""
            SELECT viewed_at FROM ml_seller_invitation_view
            WHERE invitation_id=? AND demand_id=? AND seller_id=?
            """,(rs,i)->rs.getTimestamp(1).toInstant(),
            invitationId,quote.demandId(),trustedSellerId);
        if(views.size()!=1)
            throw new IllegalStateException("View acknowledgement lineage mismatch");
        return views.getFirst();
    }
}
