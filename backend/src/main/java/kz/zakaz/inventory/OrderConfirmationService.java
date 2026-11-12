package kz.zakaz.inventory;

import java.time.Instant;
import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.deal.*;
import kz.zakaz.domain.types.*;
import kz.zakaz.location.*;
import kz.zakaz.order.*;
import kz.zakaz.security.SellerIdentityService;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * R2.1 atomic seller confirmation. All reserves, order state and one outbox
 * record share the same PostgreSQL transaction. Lock order then SKU balances
 * in globally stable order; no external calls or ML within transaction.
 */
@Service
public class OrderConfirmationService {
    private final SellerIdentityService identity;
    private final CustomerOrderRepository orders;
    private final BidAcceptanceRepository acceptances;
    private final SellerBidSnapshotRepository bids;
    private final SellerBidQuoteRepository quotes;
    private final InventoryLocationRepository locations;
    private final SellerDeliveryAreaRepository deliveryAreas;
    private final CatalogSkuFactsResolver catalog;
    private final SellerSkuRepository skus;
    private final InventoryBalanceRepository balances;
    private final InventoryReservationRepository reservations;
    private final OrderOutboxEventRepository outbox;

    public OrderConfirmationService(SellerIdentityService identity,CustomerOrderRepository orders,
            BidAcceptanceRepository acceptances,SellerBidSnapshotRepository bids,
            SellerBidQuoteRepository quotes,InventoryLocationRepository locations,
            SellerDeliveryAreaRepository deliveryAreas,CatalogSkuFactsResolver catalog,
            SellerSkuRepository skus,InventoryBalanceRepository balances,
            InventoryReservationRepository reservations,OrderOutboxEventRepository outbox) {
        this.identity=identity;this.orders=orders;this.acceptances=acceptances;this.bids=bids;
        this.quotes=quotes;this.locations=locations;this.deliveryAreas=deliveryAreas;this.catalog=catalog;
        this.skus=skus;this.balances=balances;this.reservations=reservations;this.outbox=outbox;
    }

    @Transactional
    public UUID confirm(JwtAuthenticationToken verifiedSeller,UUID sellerId,UUID orderId,Instant now){
        Objects.requireNonNull(sellerId);Objects.requireNonNull(orderId);Objects.requireNonNull(now);
        identity.requireSellerAdmin(verifiedSeller,sellerId);
        var order=orders.findForUpdate(orderId)
            .orElseThrow(()->new NoSuchElementException("Order not found"));
        if(!order.getSellerId().equals(sellerId))
            throw new SecurityException("Seller does not own order");
        if(order.getStatus()==OrderStatus.CONFIRMED) return orderId; // safe repeat after auth
        if(order.getStatus()!=OrderStatus.PLACED)
            throw new IllegalStateException("Order cannot be confirmed");
        var accepted=acceptances.findByOrderId(orderId)
            .orElseThrow(()->new IllegalStateException("Missing bid acceptance"));
        if(order.getAcceptedBidId()==null || !accepted.getBidId().equals(order.getAcceptedBidId())
                || !accepted.getBuyerId().equals(order.getBuyerId()))
            throw new IllegalStateException("Accepted bid linkage mismatch");
        var bid=bids.findById(order.getAcceptedBidId())
            .orElseThrow(()->new IllegalStateException("Accepted bid lost"));
        if(!bid.getSellerId().equals(sellerId))
            throw new IllegalStateException("Bid seller mismatch");
        var quote=quotes.findById(bid.getId())
            .orElseThrow(()->new IllegalStateException("Missing fulfillment quote"));
        if(quote.getAllInAmount().compareTo(order.getTotalAmount())!=0)
            throw new IllegalStateException("Immutable order amount mismatch");
        var origin=locations.findById(quote.getOriginLocationId())
            .orElseThrow(()->new IllegalStateException("Origin location missing"));
        if(!origin.isActive() || !sellerId.equals(origin.getSellerId())
                || !origin.getCity().equals(quote.getOriginCity())
                || !origin.getRegionCode().equals(quote.getOriginRegionCode()))
            throw new IllegalStateException("Origin location no longer valid");
        if(quote.getMethod()==SellerBidQuoteSnapshot.Method.PICKUP) {
            if(!origin.isPublicPickupEnabled())throw new IllegalStateException("Pickup disabled");
        } else if(!deliveryAreas.existsBySellerIdAndCityAndActiveTrue(sellerId,quote.getDestinationCity())) {
            throw new IllegalStateException("Delivery destination no longer served");
        }
        var lines=new ArrayList<>(order.getLines());
        if(lines.isEmpty())throw new IllegalStateException("Order has no lines");
        lines.sort(Comparator.comparing(l->l.getSkuId().toString()));
        var held=new ArrayList<InventoryReservation>();
        for(var line:lines) {
            var sku=skus.findById(line.getSkuId()).orElseThrow();
            var facts=catalog.resolveForSeller(sellerId,line.getSkuId());
            if(sku.getTrackingMode()!=StockTrackingMode.TRACKED
                    ||sku.getSalesUnit()==null||sku.getSalesUnit()!=line.getUnit()
                    ||!facts.sellerId().equals(sellerId))
                throw new IllegalStateException("Unqualified inventory SKU");
            var balance=balances.findForUpdate(sellerId,origin.getId(),line.getSkuId())
                .orElseThrow(()->new InsufficientStockException("No inventory balance for SKU"));
            balance.reserve(line.getQuantity());
            held.add(InventoryReservation.hold(orderId,line.getId(),sellerId,origin.getId(),
                line.getSkuId(),line.getQuantity()));
        }
        reservations.saveAllAndFlush(held);
        order.confirm();
        orders.flush();
        outbox.saveAndFlush(OrderOutboxEvent.orderConfirmed(order,now));
        return order.getId();
    }
}
