package kz.zakaz.deal;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.*;
import kz.zakaz.location.*;
import kz.zakaz.order.*;
import kz.zakaz.security.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * INTERNAL R2.0B acceptance, not a public checkout API. No stock reservations
 * are made at PLACED. Row-lock demand serializes acceptance and bid revision.
 */
@Service
public class BidAcceptanceService {
    private final BuyerIdentityService identities;
    private final BuyerDemandSnapshotRepository demands;
    private final SellerBidSnapshotRepository bids;
    private final BidAcceptanceRepository acceptances;
    private final SellerBidQuoteRepository quotes;
    private final SellerSkuRepository skus;
    private final CatalogSkuFactsResolver catalog;
    private final InventoryLocationRepository locations;
    private final SellerDeliveryAreaRepository areas;
    private final CustomerOrderRepository orders;
    private final OrderNumberGenerator numbers;

    public BidAcceptanceService(BuyerIdentityService identities,BuyerDemandSnapshotRepository demands,
        SellerBidSnapshotRepository bids,BidAcceptanceRepository acceptances,
        SellerBidQuoteRepository quotes,SellerSkuRepository skus,CatalogSkuFactsResolver catalog,
        InventoryLocationRepository locations,SellerDeliveryAreaRepository areas,
        CustomerOrderRepository orders,OrderNumberGenerator numbers) {
        this.identities=identities;this.demands=demands;this.bids=bids;this.acceptances=acceptances;
        this.quotes=quotes;this.skus=skus;this.catalog=catalog;this.locations=locations;
        this.areas=areas;this.orders=orders;this.numbers=numbers;
    }

    @Transactional
    public UUID accept(JwtAuthenticationToken verifiedBuyer,UUID demandId,UUID bidId,
                       String idempotencyKey,Instant now) {
        Objects.requireNonNull(demandId);Objects.requireNonNull(bidId);Objects.requireNonNull(now);
        if(idempotencyKey==null||!idempotencyKey.matches("[A-Za-z0-9_.:-]{1,80}"))
            throw new IllegalArgumentException("Idempotency key required");
        var demand=demands.findForUpdate(demandId)
            .orElseThrow(()->new NoSuchElementException("Demand not found"));
        UUID buyerId=identities.requireBuyer(verifiedBuyer,demand.getBuyerId());
        String fingerprint=fingerprint(demandId,bidId);
        var sameKey=acceptances.findByBuyerIdAndIdempotencyKey(buyerId,idempotencyKey);
        if(sameKey.isPresent()) {
            if(!fingerprint.equals(sameKey.get().getRequestFingerprint()))
                throw new IdempotencyConflictException("Same key with different request");
            return sameKey.get().getOrderId();
        }
        if(acceptances.existsByDemandId(demandId))
            throw new IdempotencyConflictException("Demand already accepted");
        var bid=bids.findById(bidId)
            .orElseThrow(()->new NoSuchElementException("Seller bid not found"));
        var quoted=quotes.findById(bidId)
            .orElseThrow(()->new IllegalStateException("No bound immutable quote"));
        SellerBid offer=bid.toDomain();
        BuyerDemand requested=demand.toDomain();
        DealQualification.validate(requested,offer,now);
        var latest=bids.findFirstByDemand_IdAndSellerIdOrderByRevisionDesc(demandId,offer.sellerId())
            .orElseThrow();
        if(!latest.getId().equals(bidId))
            throw new IllegalStateException("Superseded seller bid");
        if(!requested.city().equals(quoted.getDestinationCity()))
            throw new IllegalStateException("Quote destination mismatch");
        if(!offer.total().add(quoted.getDeliveryFee()).setScale(2)
                .equals(quoted.getAllInAmount()))
            throw new IllegalStateException("Quoted price mismatch");
        var location=locations.findById(quoted.getOriginLocationId())
            .orElseThrow(()->new IllegalStateException("Origin location missing"));
        if(!location.isActive()||!offer.sellerId().equals(location.getSellerId())
               ||!quoted.getOriginCity().equals(location.getCity())
               ||!quoted.getOriginRegionCode().equals(location.getRegionCode()))
            throw new IllegalStateException("Origin location changed");
        if(quoted.getMethod()==SellerBidQuoteSnapshot.Method.PICKUP) {
            if(!location.isPublicPickupEnabled())
                throw new IllegalStateException("Pickup disabled");
        } else if(!areas.existsBySellerIdAndCityAndActiveTrue(offer.sellerId(),requested.city())) {
            throw new IllegalStateException("Delivery area no longer approved");
        }
        var demandLines=requested.lines().stream()
            .collect(java.util.stream.Collectors.toMap(BuyerDemand.DemandLine::productId,l->l));
        var drafts=new ArrayList<OrderLine.Draft>();
        for(var line:offer.lines()) {
            var fact=catalog.resolveForSeller(offer.sellerId(),line.skuId());
            var sku=skus.findById(line.skuId()).orElseThrow();
            var wanted=demandLines.get(line.productId());
            if(wanted==null||wanted.unit()==null||sku.getSalesUnit()==null
                ||sku.getSalesUnit()!=wanted.unit()
                ||sku.getTrackingMode()!=StockTrackingMode.TRACKED
                ||!fact.productId().equals(line.productId()))
                throw new IllegalStateException("SKU unit/product is not qualified");
            drafts.add(new OrderLine.Draft(line.skuId(),line.quantity(),sku.getSalesUnit(),
                line.unitPrice(),quoted.getOriginCity(),quoted.getOriginRegionCode()));
        }
        CustomerOrder order=CustomerOrder.placedFromBid(offer.sellerId(),buyerId,
            numbers.generate(),drafts,bidId,quoted.getDeliveryFee(),now);
        if(order.getTotalAmount().compareTo(quoted.getAllInAmount())!=0)
            throw new IllegalStateException("Order total differs from quoted amount");
        orders.saveAndFlush(order);
        acceptances.saveAndFlush(BidAcceptance.accept(buyerId,demandId,bidId,idempotencyKey,
            fingerprint,order.getId()));
        return order.getId();
    }
    private static String fingerprint(UUID demandId,UUID bidId) {
        try {
            var hash=MessageDigest.getInstance("SHA-256")
                .digest((demandId+"|"+bidId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
