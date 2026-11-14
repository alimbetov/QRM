package kz.zakaz.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.UUID;
import kz.zakaz.catalog.CatalogPublicPriceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Server-managed draft catalog prices; never permits a client-supplied seller actor. */
@RestController
@ConditionalOnProperty(name = "zakaz.qr.http.enabled", havingValue = "true")
@RequestMapping("/api/v1/sellers/{sellerId}/listings/{listingId}/skus")
public class SellerPublicPriceController {
    public record PriceRequest(@NotNull BigDecimal priceKzt) {}

    private final SellerIdentityService identities;
    private final CatalogPublicPriceService prices;

    public SellerPublicPriceController(SellerIdentityService identities,
                                       CatalogPublicPriceService prices) {
        this.identities = identities;
        this.prices = prices;
    }

    @PutMapping("/{skuId}/price")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void price(@PathVariable UUID sellerId, @PathVariable UUID listingId,
                      @PathVariable UUID skuId, @Valid @RequestBody PriceRequest request,
                      JwtAuthenticationToken actor) {
        var owner = identities.requireCatalogEditor(actor, sellerId);
        try {
            prices.setDraftPrice(owner, listingId, skuId, request.priceKzt());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Listing already published");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid KZT price");
        }
    }
}
