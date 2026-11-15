package kz.zakaz.intelligence.events;

import java.util.UUID;
import kz.zakaz.security.SellerIdentityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/**
 * Explicit seller UI acknowledgement AFTER rendering the invitation content.
 * It cannot be invoked by buyers/anonymous users, and does not send push/SMS.
 */
@RestController
@ConditionalOnProperty(name={
    "zakaz.ml.r3.exposure.enabled",
    "zakaz.ml.r2.consent-api.enabled",
    "zakaz.seller.bid-workspace.enabled"},havingValue="true")
@RequestMapping("/api/v1/sellers/{sellerId}/invitations/{invitationId}")
public class MlInvitationViewController {
    private final SellerIdentityService identity;
    private final MlInvitationViewService views;

    public MlInvitationViewController(SellerIdentityService identity,
                                      MlInvitationViewService views) {
        this.identity=identity;this.views=views;
    }

    @PostMapping("/view")
    public ResponseEntity<Void> acknowledge(@PathVariable UUID sellerId,
                                            @PathVariable UUID invitationId,
                                            JwtAuthenticationToken actor) {
        var owner=identity.requireSellerAdmin(actor,sellerId);
        views.acknowledge(owner,invitationId);
        return ResponseEntity.noContent()
            .cacheControl(CacheControl.noStore()).build();
    }
}
