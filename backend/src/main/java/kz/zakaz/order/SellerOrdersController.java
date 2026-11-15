package kz.zakaz.order;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="zakaz.seller.studio.enabled",havingValue="true")
@RequestMapping("/api/v1/sellers/{sellerId}/orders")
public class SellerOrdersController {
    private final SellerOrderReadService service;
    public SellerOrdersController(SellerOrderReadService service){this.service=service;}

    @GetMapping
    public ResponseEntity<SellerOrderReadService.Page> list(
        @PathVariable UUID sellerId,
        @RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="20") int size,
        @RequestParam(defaultValue="ALL") String status,
        JwtAuthenticationToken actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.list(actor,sellerId,page,size,status));
    }
}
