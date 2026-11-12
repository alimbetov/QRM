package kz.zakaz.security;

import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trust anchor is verified JWT issuer+subject. Catalog role is checked against
 * authoritative seller_membership table, not bearer claims of seller_id/role.
 */
@Service
public class SellerIdentityService {
    private final SellerMembershipRepository memberships;
    public SellerIdentityService(SellerMembershipRepository memberships) {
        this.memberships=memberships;
    }
    @Transactional(readOnly=true)
    public UUID requireCatalogEditor(JwtAuthenticationToken actor,UUID requestedSellerId) {
        if(actor==null || !actor.isAuthenticated() || requestedSellerId==null)
            throw new AccessDeniedException("Authenticated merchant membership required");
        var jwt=actor.getToken();
        if(jwt==null || jwt.getIssuer()==null || jwt.getSubject()==null)
            throw new AccessDeniedException("Issuer and subject required");
        var membership=memberships.findByIssuerAndSubjectAndSellerIdAndActiveTrue(
                jwt.getIssuer().toString(),jwt.getSubject(),requestedSellerId)
            .orElseThrow(()->new AccessDeniedException("No active seller membership"));
        if(!membership.canEditCatalog())
            throw new AccessDeniedException("Insufficient seller role");
        return membership.getSellerId();
    }
}
