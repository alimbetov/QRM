package kz.zakaz.deal;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/** Exposes quotes only to the authenticated owner of the corresponding demand. */
@RestController
@ConditionalOnProperty(name="zakaz.commerce.http.enabled",havingValue="true")
@RequestMapping("/api/v1/buyers/demands")
public class BuyerBidReadController {
    private final BuyerBidReadService reads;
    public BuyerBidReadController(BuyerBidReadService reads) { this.reads=reads; }

    @GetMapping("/{demandId}/bids")
    public BuyerBidReadService.View bids(@PathVariable UUID demandId,
                                          JwtAuthenticationToken actor) {
        return reads.read(actor,demandId);
    }
}
