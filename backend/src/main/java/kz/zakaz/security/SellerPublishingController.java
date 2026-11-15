package kz.zakaz.security;

import java.util.UUID;
import kz.zakaz.catalog.CatalogPublishingService;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** The request cannot supply an authoritative seller actor; identity is JWT+DB membership. */
@RestController
@RequestMapping("/api/v1/sellers/{sellerId}/listings")
public class SellerPublishingController {
    private final SellerIdentityService identity;
    private final CatalogPublishingService publishing;
    public SellerPublishingController(SellerIdentityService identity,CatalogPublishingService publishing){
        this.identity=identity;this.publishing=publishing;
    }
    @PostMapping("/{listingId}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void publish(@PathVariable UUID sellerId,@PathVariable UUID listingId,
                        JwtAuthenticationToken principal){
        UUID validatedSeller=identity.requireCatalogEditor(principal,sellerId);
        try {
            publishing.publishForSeller(validatedSeller,listingId);
        } catch(SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Listing owner mismatch");
        } catch(IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Listing is incomplete");
        } catch(IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Listing cannot be published");
        }
    }
}
