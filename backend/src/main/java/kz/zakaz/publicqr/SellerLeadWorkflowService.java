package kz.zakaz.publicqr;

import java.util.*;
import kz.zakaz.security.SellerIdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A seller marks a guest callback CONTACTED or DECLINED once.
 * This is not a phone verification, order, purchase, or guaranteed call.
 * Original lead ciphertext and consent history are never rewritten.
 */
@Service
public class SellerLeadWorkflowService {
    public enum Status { CONTACTED, DECLINED }
    private final SellerIdentityService identity;
    private final JdbcTemplate jdbc;
    public SellerLeadWorkflowService(SellerIdentityService identity,JdbcTemplate jdbc) {
        this.identity=identity;this.jdbc=jdbc;
    }

    @Transactional
    public void mark(JwtAuthenticationToken actor,UUID sellerId,UUID leadId,Status status) {
        UUID owner=identity.requireSellerAdmin(actor,sellerId);
        Objects.requireNonNull(leadId);Objects.requireNonNull(status);
        var id=jdbc.query("""
            SELECT p.id FROM qr_purchase_lead p
            JOIN catalog_listing listing ON listing.id=p.listing_id
            WHERE p.id=? AND listing.seller_id=?
            FOR UPDATE OF p
            """,(rs,i)->rs.getObject(1,UUID.class),leadId,owner);
        if(id.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var old=jdbc.query("""
            SELECT status FROM qr_lead_workflow WHERE lead_id=?
            """,(rs,i)->rs.getString(1),leadId);
        if(!old.isEmpty()){
            if(old.getFirst().equals(status.name()))return;
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Guest callback was already processed");
        }
        jdbc.update("""
            INSERT INTO qr_lead_workflow
                (lead_id,seller_id,status,actor_issuer,actor_subject,updated_at)
            VALUES (?,?,?,?,?,clock_timestamp())
            """,leadId,owner,status.name(),
            actor.getToken().getIssuer().toString(),actor.getToken().getSubject());
    }
}
