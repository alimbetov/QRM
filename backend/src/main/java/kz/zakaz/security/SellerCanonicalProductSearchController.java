package kz.zakaz.security;

import java.util.List;
import java.util.UUID;
import kz.zakaz.catalog.SellerCanonicalProductSearchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Seller catalog product selection only. The caller cannot enumerate
 * unpublished canonical products without current server-side membership.
 */
@RestController
@ConditionalOnProperty(name="zakaz.seller.workspace.enabled",havingValue="true")
@RequestMapping("/api/v1/sellers/{sellerId}/catalog-products")
public class SellerCanonicalProductSearchController {
    private final SellerIdentityService identities;
    private final SellerCanonicalProductSearchService search;

    public SellerCanonicalProductSearchController(SellerIdentityService identities,
        SellerCanonicalProductSearchService search) {
        this.identities=identities;this.search=search;
    }

    @GetMapping
    public List<SellerCanonicalProductSearchService.ProductOption> products(
        @PathVariable UUID sellerId,@RequestParam String query,
        JwtAuthenticationToken actor) {
        identities.requireCatalogEditor(actor,sellerId);
        try {
            return search.search(query);
        } catch(IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid canonical product search");
        }
    }
}
