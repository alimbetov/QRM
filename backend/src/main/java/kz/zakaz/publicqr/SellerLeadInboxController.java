package kz.zakaz.publicqr;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="zakaz.qr.http.enabled",havingValue="true")
@RequestMapping("/api/v1/sellers/{sellerId}/leads")
public class SellerLeadInboxController {
    private final SellerLeadInboxService inbox;
    public SellerLeadInboxController(SellerLeadInboxService inbox){this.inbox=inbox;}
    @GetMapping
    public ResponseEntity<List<SellerLeadInboxService.LeadView>> get(
        @PathVariable UUID sellerId,
        @RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="20") int size,
        JwtAuthenticationToken actor){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(inbox.read(actor,sellerId,page,size));
    }
}
