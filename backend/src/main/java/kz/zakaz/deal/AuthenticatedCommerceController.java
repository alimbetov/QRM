package kz.zakaz.deal;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import kz.zakaz.domain.types.MeasurementUnit;
import kz.zakaz.inventory.*;
import kz.zakaz.order.BuyerOrderReadService;
import kz.zakaz.security.*;
import org.springframework.http.*;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/**
 * Real authenticated HTTP entry points. Identity comes from verified JWT +
 * server-managed account/membership, never from a client-supplied buyerId/sellerId.
 * No inventory is reserved at PLACED; only SELLER_ADMIN confirmation reserves.
 */
@RestController
@ConditionalOnProperty(name="zakaz.commerce.http.enabled",havingValue="true")
@RequestMapping("/api/v1")
public class AuthenticatedCommerceController {
    public record DemandLineInput(@NotNull UUID productId,
        @NotNull @DecimalMin("0.001") BigDecimal quantity,
        @DecimalMin("0.0") BigDecimal maxUnitPrice,
        @NotNull MeasurementUnit unit){}
    public record DemandInput(@NotBlank String city, @NotNull Instant expiresAt,
         @NotEmpty @Size(max=50) List<@Valid DemandLineInput> lines){}
    public record BidLineInput(@NotNull UUID productId,@NotNull UUID skuId,
         @NotNull @DecimalMin("0.001") BigDecimal quantity,
         @NotNull @DecimalMin("0.0") BigDecimal unitPrice){}
    public record BidInput(@Min(1) int revision,@NotNull Instant expiresAt,
         @NotEmpty @Size(max=50) List<@Valid BidLineInput> lines,
         @NotNull SellerBidQuoteSnapshot.Method method,
         @NotNull UUID originLocationId,
         @NotNull @DecimalMin("0.0") BigDecimal deliveryFee){}
    public record DemandCreated(UUID demandId){}
    public record BidCreated(UUID bidId){}
    public record OrderCreated(UUID orderId){}

    @org.springframework.beans.factory.annotation.Autowired
    private Environment environment;
    private final BuyerIdentityService buyerIdentity;
    private final BuyerOrderReadService orderReads;
    private final SellerIdentityService sellerIdentity;
    private final DealPersistenceService deals;
    private final SellerBidSubmissionService bids;
    private final BidAcceptanceService acceptance;
    private final OrderConfirmationService confirmation;
    private final OrderCancellationService cancellation;
    public AuthenticatedCommerceController(BuyerIdentityService buyerIdentity,
         SellerIdentityService sellerIdentity,DealPersistenceService deals,
         SellerBidSubmissionService bids,BidAcceptanceService acceptance,
         OrderConfirmationService confirmation,OrderCancellationService cancellation,
         BuyerOrderReadService orderReads){
        this.buyerIdentity=buyerIdentity;this.sellerIdentity=sellerIdentity;
        this.deals=deals;this.bids=bids;this.acceptance=acceptance;
        this.confirmation=confirmation;this.cancellation=cancellation;
        this.orderReads=orderReads;
    }
    @PostMapping("/buyers/demands")
    public ResponseEntity<DemandCreated> createDemand(@Valid @RequestBody DemandInput body,
                                                       JwtAuthenticationToken actor){
        var buyerId=buyerIdentity.requireBuyer(actor);
        var now=Instant.now();
        if(!body.expiresAt().isAfter(now)||body.expiresAt().isAfter(now.plus(Duration.ofDays(30))))
            throw new IllegalArgumentException("Demand expiry out of range");
        var lines=body.lines().stream()
            .map(l->new BuyerDemand.DemandLine(l.productId(),l.quantity(),
                                               l.maxUnitPrice(),l.unit())).toList();
        var demand=new BuyerDemand(UUID.randomUUID(),buyerId,body.city(),body.expiresAt(),lines);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new DemandCreated(deals.createDemand(demand,now)));
    }
    @PostMapping("/sellers/{sellerId}/demands/{demandId}/bids")
    public ResponseEntity<BidCreated> submitBid(@PathVariable UUID sellerId,
         @PathVariable UUID demandId,@Valid @RequestBody BidInput body,
         JwtAuthenticationToken actor){
        var seller=sellerIdentity.requireSellerAdmin(actor,sellerId);
        // When invitation-only bidding is enabled, the legacy demand-UUID path
        // must not bypass buyer consent or seller inbox authorization.
        if(environment.getProperty("zakaz.seller.bid-workspace.enabled",Boolean.class,false))
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.FORBIDDEN,"Use the consented invitation bid endpoint");
        var now=Instant.now();
        if(!body.expiresAt().isAfter(now)||body.expiresAt().isAfter(now.plus(Duration.ofDays(30))))
            throw new IllegalArgumentException("Bid expiry out of range");
        var lines=body.lines().stream().map(l->new SellerBid.BidLine(
            l.productId(),l.skuId(),l.quantity(),l.unitPrice())).toList();
        var bid=new SellerBid(UUID.randomUUID(),demandId,seller,body.revision(),
                              body.expiresAt(),lines);
        var quote=new SellerBidSubmissionService.QuoteRequest(
            body.method(),body.originLocationId(),body.deliveryFee());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new BidCreated(bids.submit(seller,bid,quote,now)));
    }
    @PostMapping("/buyers/demands/{demandId}/bids/{bidId}/accept")
    public ResponseEntity<OrderCreated> acceptBid(@PathVariable UUID demandId,
         @PathVariable UUID bidId,
         @RequestHeader(value="Idempotency-Key",required=false) String key,
         JwtAuthenticationToken actor){
        var id=acceptance.accept(actor,demandId,bidId,key,Instant.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(new OrderCreated(id));
    }
    @GetMapping("/buyers/orders/{orderId}")
    public BuyerOrderReadService.View buyerOrder(@PathVariable UUID orderId,
                                                  JwtAuthenticationToken actor){
        return orderReads.read(actor,orderId);
    }
    @PostMapping("/buyers/orders/{orderId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelBuyerOrder(@PathVariable UUID orderId,JwtAuthenticationToken actor) {
        cancellation.cancelByBuyer(actor,orderId,Instant.now());
    }
    @PostMapping("/sellers/{sellerId}/orders/{orderId}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmOrder(@PathVariable UUID sellerId,@PathVariable UUID orderId,
                             JwtAuthenticationToken actor){
        confirmation.confirm(actor,sellerId,orderId,Instant.now());
    }
    @PostMapping("/sellers/{sellerId}/orders/{orderId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelOrder(@PathVariable UUID sellerId,@PathVariable UUID orderId,
                            JwtAuthenticationToken actor){
        cancellation.cancelBySeller(actor,sellerId,orderId,Instant.now());
    }
}
