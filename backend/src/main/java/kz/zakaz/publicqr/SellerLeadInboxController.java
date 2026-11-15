package kz.zakaz.publicqr;

import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="zakaz.qr.http.enabled",havingValue="true")
@RequestMapping("/api/v1/sellers/{sellerId}/leads")
public class SellerLeadInboxController {
    public record StatusInput(@NotNull SellerLeadWorkflowService.Status status){}
    private final SellerLeadInboxService inbox;
    private final SellerLeadWorkflowService workflow;
    public SellerLeadInboxController(SellerLeadInboxService inbox,SellerLeadWorkflowService workflow){
        this.inbox=inbox;this.workflow=workflow;
    }
    @GetMapping
    public ResponseEntity<List<SellerLeadInboxService.LeadView>> get(
        @PathVariable UUID sellerId,
        @RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="20") int size,
        JwtAuthenticationToken actor){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(inbox.read(actor,sellerId,page,size));
    }
    @PutMapping("/{leadId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mark(@PathVariable UUID sellerId,@PathVariable UUID leadId,
        @Valid @RequestBody StatusInput request,JwtAuthenticationToken actor){
        workflow.mark(actor,sellerId,leadId,request.status());
    }
}
