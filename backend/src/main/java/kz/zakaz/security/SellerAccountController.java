package kz.zakaz.security;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="zakaz.seller.studio.enabled",havingValue="true")
@RequestMapping("/api/v1/sellers")
public class SellerAccountController {
    public record Seller(UUID sellerId,String role){}
    private final SellerMembershipRepository memberships;
    public SellerAccountController(SellerMembershipRepository memberships){
        this.memberships=memberships;
    }
    /** The JWT provides issuer+subject, never a claimed seller_id. */
    @GetMapping("/me")
    public ResponseEntity<List<Seller>> current(JwtAuthenticationToken actor){
        if(actor==null||!actor.isAuthenticated()||actor.getToken()==null
            ||actor.getToken().getIssuer()==null||actor.getToken().getSubject()==null)
            throw new AccessDeniedException("Verified merchant required");
        var jwt=actor.getToken();
        var authorized=memberships.findByIssuerAndSubjectAndActiveTrue(
            jwt.getIssuer().toString(),jwt.getSubject()).stream()
            .filter(SellerMembership::canConfirmOrders)
            .map(m->new Seller(m.getSellerId(),m.getRole().name()))
            .sorted(Comparator.comparing(s->s.sellerId().toString())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(authorized);
    }
}
