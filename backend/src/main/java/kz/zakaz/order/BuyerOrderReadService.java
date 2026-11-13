package kz.zakaz.order;

import java.util.*;
import kz.zakaz.domain.types.MeasurementUnit;
import kz.zakaz.security.BuyerIdentityService;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Buyer-owned immutable economic snapshot and current status. No seller PII. */
@Service
public class BuyerOrderReadService {
    public record Line(UUID skuId,String quantity,MeasurementUnit unit,String lineTotal){}
    public record View(UUID orderId,String publicNumber,UUID sellerId,String status,
                       String totalAmount,String deliveryFee,String currency,
                       List<Line> lines){}
    private final BuyerIdentityService identity;
    private final CustomerOrderRepository orders;
    public BuyerOrderReadService(BuyerIdentityService identity,CustomerOrderRepository orders){
        this.identity=identity;this.orders=orders;
    }
    @Transactional(readOnly=true)
    public View read(JwtAuthenticationToken actor,UUID orderId){
        Objects.requireNonNull(orderId);
        var order=orders.findById(orderId)
            .orElseThrow(()->new NoSuchElementException("Order not found"));
        identity.requireBuyer(actor,order.getBuyerId());
        var lines=order.getLines().stream().map(l->new Line(
            l.getSkuId(),l.getQuantity().toPlainString(),l.getUnit(),l.getLineTotal().toPlainString())).toList();
        return new View(order.getId(),order.getPublicNumber(),order.getSellerId(),
            order.getStatus().name(),order.getTotalAmount().toPlainString(),
            order.getDeliveryFee().toPlainString(),
            order.getCurrencyCode(),lines);
    }
}
