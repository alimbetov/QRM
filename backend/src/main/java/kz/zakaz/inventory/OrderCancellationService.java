package kz.zakaz.inventory;

import java.time.Instant;
import java.util.*;
import kz.zakaz.domain.types.OrderStatus;
import kz.zakaz.order.*;
import kz.zakaz.security.SellerIdentityService;
import kz.zakaz.security.BuyerIdentityService;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal seller-side cancellation for PLACED or CONFIRMED (before processing).
 * Status, stock release, immutable movement audit and outbox share one transaction.
 */
@Service
public class OrderCancellationService {
    private final SellerIdentityService identity;
    private final BuyerIdentityService buyerIdentity;
    private final CustomerOrderRepository orders;
    private final InventoryReservationRepository reservations;
    private final InventoryBalanceRepository balances;
    private final InventoryMovementRepository movements;
    private final OrderOutboxEventRepository outbox;
    public OrderCancellationService(SellerIdentityService identity,BuyerIdentityService buyerIdentity,
        CustomerOrderRepository orders,
        InventoryReservationRepository reservations,InventoryBalanceRepository balances,
        InventoryMovementRepository movements,OrderOutboxEventRepository outbox){
        this.identity=identity;this.buyerIdentity=buyerIdentity;this.orders=orders;this.reservations=reservations;
        this.balances=balances;this.movements=movements;this.outbox=outbox;
    }

    /**
     * A buyer may cancel only before seller confirmation and stock reservation.
     * Locking order first serializes buyer cancellation against seller confirmation.
     * A completed cancellation can be retried; foreign buyers remain forbidden.
     * CONFIRMED/PROCESSING cancellation requires a separate consent workflow.
     */
    @Transactional
    public UUID cancelByBuyer(JwtAuthenticationToken actor,UUID orderId,Instant now) {
        Objects.requireNonNull(orderId);Objects.requireNonNull(now);
        UUID buyerId=buyerIdentity.requireBuyer(actor);
        var order=orders.findForUpdate(orderId)
            .orElseThrow(()->new NoSuchElementException("Order not found"));
        if(!order.getBuyerId().equals(buyerId))
            throw new org.springframework.security.access.AccessDeniedException("Buyer does not own order");
        if(order.getStatus()==OrderStatus.CANCELLED_BY_BUYER)return orderId;
        if(order.getStatus()!=OrderStatus.PLACED)
            throw new IllegalStateException("Buyer cancellation requires unconfirmed order");
        if(!reservations.findByOrderId(orderId).isEmpty())
            throw new IllegalStateException("PLACED order unexpectedly has inventory reservations");
        order.cancelByBuyer();
        orders.flush();
        outbox.saveAndFlush(OrderOutboxEvent.orderCancelledByBuyer(order,now));
        return orderId;
    }

    @Transactional
    public UUID cancelBySeller(JwtAuthenticationToken actor,UUID sellerId,
                               UUID orderId,Instant now){
        Objects.requireNonNull(sellerId);Objects.requireNonNull(orderId);Objects.requireNonNull(now);
        identity.requireSellerAdmin(actor,sellerId);
        var order=orders.findForUpdate(orderId)
            .orElseThrow(()->new NoSuchElementException("Order not found"));
        if(!order.getSellerId().equals(sellerId))
            throw new SecurityException("Seller does not own order");
        if(order.getStatus()==OrderStatus.CANCELLED_BY_SELLER)return orderId;
        if(order.getStatus()!=OrderStatus.PLACED && order.getStatus()!=OrderStatus.CONFIRMED)
            throw new IllegalStateException("Order cannot be cancelled by seller in current state");
        var held=reservations.findByOrderId(orderId);
        if(order.getStatus()==OrderStatus.PLACED) {
            if(!held.isEmpty())throw new IllegalStateException("PLACED order already has reservations");
        } else {
            if(held.size()!=order.getLines().size())
                throw new IllegalStateException("Missing/extra reservations for confirmed order");
            var ordered=new ArrayList<>(held);
            ordered.sort(Comparator.comparing(r->r.getSkuId().toString()));
            var audit=new ArrayList<InventoryMovement>();
            for(var reservation:ordered) {
                if(!"RESERVED".equals(reservation.getStatus()))
                    throw new IllegalStateException("Reservation cannot be released");
                if(!sellerId.equals(reservation.getSellerId()))
                    throw new IllegalStateException("Foreign seller reservation");
                var balance=balances.findForUpdate(sellerId,reservation.getLocationId(),reservation.getSkuId())
                    .orElseThrow(()->new IllegalStateException("Missing inventory balance"));
                balance.release(reservation.getQuantity());
                reservation.release();
                audit.add(InventoryMovement.release(orderId,reservation.getOrderLineId(),
                    sellerId,reservation.getLocationId(),reservation.getSkuId(),reservation.getQuantity(),
                    actor.getToken().getIssuer().toString(),actor.getToken().getSubject(),now));
            }
            reservations.flush();
            movements.saveAllAndFlush(audit);
        }
        order.cancelBySeller();
        orders.flush();
        outbox.saveAndFlush(OrderOutboxEvent.orderCancelledBySeller(order,now));
        return order.getId();
    }
}
