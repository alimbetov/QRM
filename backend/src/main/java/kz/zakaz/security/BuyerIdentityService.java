package kz.zakaz.security;

import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal only. JWT must be verified by configured Spring Security decoder
 * prior to this call; issuer+subject are resolved against trusted account DB.
 */
@Service
public class BuyerIdentityService {
    private final BuyerAccountLinkRepository accounts;
    private final boolean requireVerifiedEmail;
    public BuyerIdentityService(BuyerAccountLinkRepository accounts,
        @Value("${zakaz.identity.require-verified-email:false}") boolean requireVerifiedEmail){
        this.accounts=accounts;
        this.requireVerifiedEmail=requireVerifiedEmail;
    }
    private void requireEmailProof(JwtAuthenticationToken jwt){
        if(requireVerifiedEmail && !Boolean.TRUE.equals(jwt.getToken().getClaim("email_verified")))
            throw new AccessDeniedException("Verified email required for buyer checkout");
    }
    @Transactional(readOnly=true)
    public UUID requireBuyer(JwtAuthenticationToken verifiedJwt){
        if(verifiedJwt==null || !verifiedJwt.isAuthenticated() ||
           verifiedJwt.getToken()==null || verifiedJwt.getToken().getIssuer()==null ||
           verifiedJwt.getToken().getSubject()==null)
            throw new AccessDeniedException("Verified buyer required");
        requireEmailProof(verifiedJwt);
        var jwt=verifiedJwt.getToken();
        return accounts.findByIssuerAndSubjectAndActiveTrue(
            jwt.getIssuer().toString(),jwt.getSubject())
            .orElseThrow(()->new AccessDeniedException("Unlinked buyer")).getBuyerId();
    }
    @Transactional(readOnly=true)
    public UUID requireBuyer(JwtAuthenticationToken verifiedJwt,UUID expectedBuyerId){
        if(verifiedJwt==null || !verifiedJwt.isAuthenticated() || expectedBuyerId==null
           || verifiedJwt.getToken()==null || verifiedJwt.getToken().getIssuer()==null
           || verifiedJwt.getToken().getSubject()==null)
            throw new AccessDeniedException("Verified buyer required");
        requireEmailProof(verifiedJwt);
        var jwt=verifiedJwt.getToken();
        var buyer=accounts.findByIssuerAndSubjectAndActiveTrue(
            jwt.getIssuer().toString(),jwt.getSubject())
            .orElseThrow(()->new AccessDeniedException("Unlinked buyer"));
        if(!buyer.getBuyerId().equals(expectedBuyerId))
            throw new AccessDeniedException("Buyer does not own demand");
        return buyer.getBuyerId();
    }
}
