package kz.zakaz.deal;

import java.util.UUID;
import kz.zakaz.security.SellerIdentityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/** Consent-scoped seller response to an actual delivered invitation. */
@RestController
@ConditionalOnProperty(
    name={"zakaz.seller.bid-workspace.enabled","zakaz.ml.r2.consent-api.enabled"},
    havingValue="true")
@RequestMapping("/api/v1/sellers/{sellerId}/invitations/{invitationId}")
public class SellerInvitationBidWorkspaceController {
    private final SellerIdentityService identity;
    private final SellerInvitationBidWorkspaceService workspace;

    public SellerInvitationBidWorkspaceController(SellerIdentityService identity,
        SellerInvitationBidWorkspaceService workspace) {
        this.identity=identity;this.workspace=workspace;
    }

    @GetMapping("/quote-context")
    public ResponseEntity<SellerInvitationBidWorkspaceService.Context> preview(
        @PathVariable UUID sellerId,@PathVariable UUID invitationId,
        JwtAuthenticationToken actor) {
        UUID owner=identity.requireSellerAdmin(actor,sellerId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(workspace.preview(owner,invitationId));
    }

    @PostMapping("/bid")
    public ResponseEntity<SellerInvitationBidWorkspaceService.Created> submit(
        @PathVariable UUID sellerId,@PathVariable UUID invitationId,
        @RequestBody SellerInvitationBidWorkspaceService.BidRequest body,
        JwtAuthenticationToken actor) {
        UUID owner=identity.requireSellerAdmin(actor,sellerId);
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(workspace.submit(owner,invitationId,body));
    }
}
