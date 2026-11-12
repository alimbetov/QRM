package kz.zakaz.inventory;

import jakarta.persistence.*;
import java.math.*;
import java.util.*;

/** Physical tracked stock per seller/location/SKU, with optimistic version. */
@Entity
@Table(name="inventory_balance",uniqueConstraints=
    @UniqueConstraint(name="uk_balance_seller_location_sku",
        columnNames={"seller_id","location_id","sku_id"}))
public class InventoryBalance {
    @Id @Column(nullable=false) private UUID id;
    @Column(name="seller_id",nullable=false,updatable=false) private UUID sellerId;
    @Column(name="location_id",nullable=false,updatable=false) private UUID locationId;
    @Column(name="sku_id",nullable=false,updatable=false) private UUID skuId;
    @Column(name="on_hand",nullable=false,precision=19,scale=3) private BigDecimal onHand;
    @Column(nullable=false,precision=19,scale=3) private BigDecimal reserved;
    @Version private Long version;
    protected InventoryBalance(){}
    public static InventoryBalance open(UUID sellerId,UUID locationId,UUID skuId,BigDecimal onHand) {
        Objects.requireNonNull(sellerId);Objects.requireNonNull(locationId);Objects.requireNonNull(skuId);
        checkQuantity(onHand,true);
        var b=new InventoryBalance();b.id=UUID.randomUUID();b.sellerId=sellerId;
        b.locationId=locationId;b.skuId=skuId;b.onHand=onHand.setScale(3);
        b.reserved=BigDecimal.ZERO.setScale(3);return b;
    }
    public void reserve(BigDecimal quantity){
        checkQuantity(quantity,false);
        if(available().compareTo(quantity)<0)
            throw new InsufficientStockException("Available stock is insufficient");
        reserved=reserved.add(quantity).setScale(3);
    }
    private static void checkQuantity(BigDecimal q,boolean allowZero){
        if(q==null||q.scale()>3 || q.precision()>19 || (allowZero?q.signum()<0:q.signum()<=0))
            throw new IllegalArgumentException("Invalid inventory quantity");
    }
    public BigDecimal available(){return onHand.subtract(reserved);}
    public BigDecimal getReserved(){return reserved;}
    public UUID getId(){return id;}
    public UUID getSellerId(){return sellerId;}
    public UUID getLocationId(){return locationId;}
    public UUID getSkuId(){return skuId;}
}
