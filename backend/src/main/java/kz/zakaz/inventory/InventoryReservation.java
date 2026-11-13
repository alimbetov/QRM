package kz.zakaz.inventory;

import jakarta.persistence.*;
import java.math.*;
import java.util.*;

/** Per-order-line stock reservation; future releases will use controlled transitions. */
@Entity @Table(name="inventory_reservation",uniqueConstraints=
    @UniqueConstraint(name="uk_reservation_order_line",columnNames="order_line_id"))
public class InventoryReservation {
    @Id @Column(nullable=false) private UUID id;
    @Version private Long version;
    @Column(name="order_id",nullable=false,updatable=false) private UUID orderId;
    @Column(name="order_line_id",nullable=false,updatable=false) private UUID orderLineId;
    @Column(name="seller_id",nullable=false,updatable=false) private UUID sellerId;
    @Column(name="location_id",nullable=false,updatable=false) private UUID locationId;
    @Column(name="sku_id",nullable=false,updatable=false) private UUID skuId;
    @Column(nullable=false,precision=19,scale=3,updatable=false) private BigDecimal quantity;
    @Column(nullable=false,length=16) private String status;
    protected InventoryReservation(){}
    public String getStatus(){return status;}
    public UUID getOrderLineId(){return orderLineId;}
    public UUID getSellerId(){return sellerId;}
    public UUID getLocationId(){return locationId;}
    public UUID getSkuId(){return skuId;}
    public BigDecimal getQuantity(){return quantity;}
    public void release(){
        if(!"RESERVED".equals(status))
            throw new IllegalStateException("Reservation is not active");
        status="RELEASED";
    }
    public static InventoryReservation hold(UUID orderId,UUID orderLineId,UUID sellerId,
                                UUID locationId,UUID skuId,BigDecimal quantity){
        Objects.requireNonNull(orderId);Objects.requireNonNull(orderLineId);
        Objects.requireNonNull(sellerId);Objects.requireNonNull(locationId);Objects.requireNonNull(skuId);
        if(quantity==null||quantity.signum()<=0||quantity.scale()>3||quantity.precision()>19)
            throw new IllegalArgumentException("Invalid reserved quantity");
        var r=new InventoryReservation();r.id=UUID.randomUUID();r.orderId=orderId;
        r.orderLineId=orderLineId;r.sellerId=sellerId;r.locationId=locationId;
        r.skuId=skuId;r.quantity=quantity;r.status="RESERVED";return r;
    }
}
