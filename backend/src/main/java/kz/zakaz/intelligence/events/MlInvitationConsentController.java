package kz.zakaz.intelligence.events;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import kz.zakaz.security.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/**
 * Explicit opt-in, per buyer demand and seller. No anonymous invitations,
 * no buyer contact information and no external notification gateway.
 */
@RestController
@ConditionalOnProperty(name="zakaz.ml.r2.consent-api.enabled",havingValue="true")
@RequestMapping("/api/v1")
public class MlInvitationConsentController {
    public record Choice(@NotNull Boolean enabled) {}
    public record Status(boolean enabled) {}

    private final MlInvitationConsentService policy;
    private final BuyerIdentityService buyerIdentity;
    private final SellerIdentityService sellerIdentity;

    public MlInvitationConsentController(MlInvitationConsentService policy,
        BuyerIdentityService buyerIdentity,SellerIdentityService sellerIdentity) {
        this.policy=policy;
        this.buyerIdentity=buyerIdentity;
        this.sellerIdentity=sellerIdentity;
    }


    @GetMapping("/buyers/demands/{demandId}/invitation-sharing")
    public org.springframework.http.ResponseEntity<Status> buyerStatus(
        @PathVariable UUID demandId,JwtAuthenticationToken actor) {
        UUID buyer=buyerIdentity.requireBuyer(actor);
        return org.springframework.http.ResponseEntity.ok()
            .cacheControl(org.springframework.http.CacheControl.noStore())
            .body(new Status(policy.demandSharingEnabled(buyer,demandId)));
    }

    @GetMapping("/sellers/{sellerId}/invitation-preference")
    public org.springframework.http.ResponseEntity<Status> sellerStatus(
        @PathVariable UUID sellerId,JwtAuthenticationToken actor) {
        UUID seller=sellerIdentity.requireSellerAdmin(actor,sellerId);
        return org.springframework.http.ResponseEntity.ok()
            .cacheControl(org.springframework.http.CacheControl.noStore())
            .body(new Status(policy.sellerInAppEnabled(seller)));
    }

    @PutMapping("/buyers/demands/{demandId}/invitation-sharing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void share(@PathVariable UUID demandId,@Valid @RequestBody Choice choice,
                      JwtAuthenticationToken actor) {
        UUID buyer=buyerIdentity.requireBuyer(actor);
        policy.setDemandSharing(buyer,demandId,choice.enabled());
    }

    @PutMapping("/sellers/{sellerId}/invitation-preference")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void preference(@PathVariable UUID sellerId,@Valid @RequestBody Choice choice,
                           JwtAuthenticationToken actor) {
        UUID seller=sellerIdentity.requireSellerAdmin(actor,sellerId);
        policy.setSellerInApp(seller,choice.enabled());
    }

    @GetMapping("/sellers/{sellerId}/invitations")
    public List<MlInvitationConsentService.InboxItem> inbox(
            @PathVariable UUID sellerId,
            @RequestParam(defaultValue="24") int limit,
            JwtAuthenticationToken actor) {
        UUID seller=sellerIdentity.requireSellerAdmin(actor,sellerId);
        return policy.listSellerInbox(seller,limit);
    }
}
