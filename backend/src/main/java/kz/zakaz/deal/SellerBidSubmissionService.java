package kz.zakaz.deal;

import java.math.*;
import java.time.Instant;
import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.*;
import kz.zakaz.location.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal, seller-authenticated submission; no public bidding HTTP endpoint.
 * Quote is written in SAME transaction as immutable SellerBid revision.
 */
@Service
public class SellerBidSubmissionService {
    public record QuoteRequest(SellerBidQuoteSnapshot.Method method,UUID originLocationId,
                               BigDecimal deliveryFee) {
        public QuoteRequest {
            Objects.requireNonNull(method);Objects.requireNonNull(originLocationId);
            Objects.requireNonNull(deliveryFee);
            if(deliveryFee.signum()<0||deliveryFee.scale()>2||deliveryFee.precision()>19)
                throw new IllegalArgumentException("Invalid delivery fee");
        }
    }
    private final DealPersistenceService deals;
    private final SellerSkuRepository skus;
    private final CatalogSkuFactsResolver catalog;
    private final InventoryLocationRepository locations;
    private final SellerDeliveryAreaRepository deliveryAreas;
    private final SellerBidQuoteRepository quotes;
    public SellerBidSubmissionService(DealPersistenceService deals,SellerSkuRepository skus,
                      CatalogSkuFactsResolver catalog,InventoryLocationRepository locations,
                      SellerDeliveryAreaRepository deliveryAreas,SellerBidQuoteRepository quotes) {
        this.deals=deals;this.skus=skus;this.catalog=catalog;this.locations=locations;
        this.deliveryAreas=deliveryAreas;this.quotes=quotes;
    }
    @Transactional
    public UUID submit(UUID trustedSellerId,SellerBid bid,QuoteRequest request,Instant now) {
        Objects.requireNonNull(trustedSellerId);Objects.requireNonNull(bid);
        Objects.requireNonNull(request);Objects.requireNonNull(now);
        if(!trustedSellerId.equals(bid.sellerId()))
            throw new SecurityException("Foreign seller");
        var d=deals.getDemand(bid.demandId());
        DealQualification.validate(d,bid,now);
        var requested=d.lines().stream().collect(java.util.stream.Collectors.toMap(
            BuyerDemand.DemandLine::productId,l->l));
        for(var line:bid.lines()) {
            var facts=catalog.resolveForSeller(trustedSellerId,line.skuId());
            var sku=skus.findById(line.skuId()).orElseThrow();
            var unit=requested.get(line.productId()).unit();
            if(unit==null||sku.getSalesUnit()==null||sku.getSalesUnit()!=unit
                    ||sku.getTrackingMode()!=StockTrackingMode.TRACKED
                    ||unit==MeasurementUnit.SERVICE || unit==MeasurementUnit.LICENSE
                    ||!facts.productId().equals(line.productId()))
                throw new IllegalArgumentException("Unverified SKU quantity unit or product");
        }
        var location=locations.findById(request.originLocationId())
            .orElseThrow(()->new IllegalArgumentException("Unknown origin location"));
        if(!location.isActive()||!trustedSellerId.equals(location.getSellerId()))
            throw new SecurityException("Origin does not belong to active seller location");
        if(request.method()==SellerBidQuoteSnapshot.Method.PICKUP) {
            if(!location.isPublicPickupEnabled())
                throw new IllegalArgumentException("Origin is not a public pickup point");
        } else {
            if(!deliveryAreas.existsBySellerIdAndCityAndActiveTrue(trustedSellerId,d.city()))
                throw new IllegalArgumentException("Seller does not serve destination");
        }
        UUID id=deals.submitBid(trustedSellerId,bid,now);
        quotes.saveAndFlush(SellerBidQuoteSnapshot.capture(id,request.method(),location.getId(),
            location.getCity(),location.getRegionCode(),d.city(),bid.total(),request.deliveryFee()));
        return id;
    }
}
