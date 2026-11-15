package kz.zakaz.publicqr;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="zakaz.qr.http.enabled",havingValue="true")
@RequestMapping("/api/v1/public/q")
public class PublicQrController {
    public record LeadRequest(String phone,Boolean consent){}
    private final PublicQrCatalogService catalog;
    private final GuestLeadService leads;
    public PublicQrController(PublicQrCatalogService catalog,GuestLeadService leads){
        this.catalog=catalog;this.leads=leads;
    }
    @GetMapping("/{token}")
    public PublicQrCatalogService.ListingView get(@PathVariable String token){
        return catalog.view(token);
    }
    @PostMapping("/{token}/leads")
    public ResponseEntity<Void> callback(@PathVariable String token,
            @RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey,
            @RequestBody LeadRequest body,HttpServletRequest servletRequest){
        leads.submit(token,idempotencyKey,body.phone(),Boolean.TRUE.equals(body.consent()),
                     servletRequest.getRemoteAddr());
        return ResponseEntity.accepted().build();
    }
}
