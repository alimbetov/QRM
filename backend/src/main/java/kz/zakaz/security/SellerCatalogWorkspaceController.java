package kz.zakaz.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.math.BigDecimal;
import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/**
 * Seller Workspace R1, opt-in. Server verifies JWT issuer/subject/scope and
 * seller_membership before any read/write. No seller ID in request body.
 */
@RestController
@ConditionalOnProperty(name="zakaz.seller.workspace.enabled",havingValue="true")
@RequestMapping("/api/v1/sellers/{sellerId}/listings")
public class SellerCatalogWorkspaceController {
    public record CreateDraftRequest(
        @NotNull UUID productId,
        @NotBlank @Size(max=80) String skuCode,
        @NotNull MeasurementUnit unit,
        @NotNull StockTrackingMode trackingMode,
        @DecimalMin("0.01") BigDecimal publicPriceKzt) {}

    private final SellerIdentityService identities;
    private final SellerCatalogWorkspaceService workspace;
    public SellerCatalogWorkspaceController(SellerIdentityService identities,
        SellerCatalogWorkspaceService workspace) {
        this.identities=identities;this.workspace=workspace;
    }

    @GetMapping
    public SellerCatalogWorkspaceService.PageView list(
        @PathVariable UUID sellerId,
        @RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="12") int size,
        JwtAuthenticationToken actor) {
        return workspace.list(identities.requireCatalogEditor(actor,sellerId),page,size);
    }

    @GetMapping("/{listingId}")
    public SellerCatalogWorkspaceService.ListingView read(
        @PathVariable UUID sellerId,@PathVariable UUID listingId,
        JwtAuthenticationToken actor) {
        return workspace.read(identities.requireCatalogEditor(actor,sellerId),listingId);
    }

    @PostMapping
    public ResponseEntity<SellerCatalogWorkspaceService.ListingView> create(
        @PathVariable UUID sellerId,@Valid @RequestBody CreateDraftRequest body,
        JwtAuthenticationToken actor) {
        UUID verifiedSeller=identities.requireCatalogEditor(actor,sellerId);
        var result=workspace.createDraft(verifiedSeller,
            new SellerCatalogWorkspaceService.Draft(
                body.productId(),body.skuCode(),body.unit(),body.trackingMode(),
                body.publicPriceKzt()));
        URI location=URI.create("/api/v1/sellers/"+verifiedSeller+
            "/listings/"+result.listingId());
        return ResponseEntity.created(location).body(result);
    }
}
