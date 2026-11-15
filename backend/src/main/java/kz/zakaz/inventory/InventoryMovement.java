package kz.zakaz.inventory;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Immutable audited inventory deltas; never rewrite a historical movement. */
@Entity
@Table(name="inventory_movement", uniqueConstraints=@UniqueConstraint(
    name="uk_inventory_movement_source",columnNames={"seller_id","kind","source_key"}))
public class InventoryMovement {
    public enum Kind { RECEIPT, RESERVATION, RELEASE }
    @Id @Column(nullable=false) private UUID id;
    @Version private Long version;
    @Column(name="seller_id",nullable=false,updatable=false) private UUID sellerId;
    @Column(name="location_id",nullable=false,updatable=false) private UUID locationId;
    @Column(name="sku_id",nullable=false,updatable=false) private UUID skuId;
    @Column(name="order_id",updatable=false) private UUID orderId;
    @Column(name="order_line_id",updatable=false) private UUID orderLineId;
    @Enumerated(EnumType.STRING)
    @Column(nullable=false,length=16,updatable=false) private Kind kind;
    @Column(name="source_key",nullable=false,length=80,updatable=false) private String sourceKey;
    @Column(name="delta_on_hand",nullable=false,precision=19,scale=3,updatable=false)
    private BigDecimal deltaOnHand;
    @Column(name="delta_reserved",nullable=false,precision=19,scale=3,updatable=false)
    private BigDecimal deltaReserved;
    @Column(name="actor_issuer",nullable=false,length=255,updatable=false) private String actorIssuer;
    @Column(name="actor_subject",nullable=false,length=255,updatable=false) private String actorSubject;
    @Column(name="occurred_at",nullable=false,updatable=false) private Instant occurredAt;
    protected InventoryMovement(){}

    private static InventoryMovement of(Kind kind, UUID sellerId,UUID locationId,UUID skuId,
       UUID orderId,UUID orderLineId, String sourceKey,BigDecimal onHand,BigDecimal reserved,
       String issuer,String subject,Instant now) {
        Objects.requireNonNull(kind);Objects.requireNonNull(sellerId);
        Objects.requireNonNull(locationId);Objects.requireNonNull(skuId);
        Objects.requireNonNull(onHand);Objects.requireNonNull(reserved);Objects.requireNonNull(now);
        if(sourceKey==null||!sourceKey.matches("[A-Za-z0-9_.:-]{1,80}")
            ||issuer==null||issuer.isBlank()||issuer.length()>255
            ||subject==null||subject.isBlank()||subject.length()>255)
            throw new IllegalArgumentException("Invalid inventory movement provenance");
        if(onHand.scale()>3 || reserved.scale()>3 ||
           onHand.precision()>19 || reserved.precision()>19)
            throw new IllegalArgumentException("Invalid movement numeric scale");
        var event=new InventoryMovement();event.id=UUID.randomUUID();event.kind=kind;
        event.sellerId=sellerId;event.locationId=locationId;event.skuId=skuId;
        event.orderId=orderId;event.orderLineId=orderLineId;event.sourceKey=sourceKey;
        event.deltaOnHand=onHand.setScale(3);event.deltaReserved=reserved.setScale(3);
        event.actorIssuer=issuer;event.actorSubject=subject;event.occurredAt=now;
        return event;
    }
    public static InventoryMovement receipt(UUID sellerId,UUID locationId,UUID skuId,
       String sourceKey,BigDecimal quantity,String issuer,String subject,Instant now) {
        if(quantity==null||quantity.signum()<=0)throw new IllegalArgumentException("Receipt quantity");
        return of(Kind.RECEIPT,sellerId,locationId,skuId,null,null,sourceKey,
            quantity,BigDecimal.ZERO,issuer,subject,now);
    }
    public static InventoryMovement reservation(UUID orderId,UUID orderLineId,
       UUID sellerId,UUID locationId,UUID skuId,BigDecimal quantity,
       String issuer,String subject,Instant now) {
        if(quantity==null||quantity.signum()<=0)throw new IllegalArgumentException("Reserve quantity");
        return of(Kind.RESERVATION,sellerId,locationId,skuId,orderId,orderLineId,
            orderLineId.toString(),BigDecimal.ZERO,quantity,issuer,subject,now);
    }
    public static InventoryMovement release(UUID orderId,UUID orderLineId,
       UUID sellerId,UUID locationId,UUID skuId,BigDecimal quantity,
       String issuer,String subject,Instant now) {
        if(quantity==null||quantity.signum()<=0)throw new IllegalArgumentException("Release quantity");
        return of(Kind.RELEASE,sellerId,locationId,skuId,orderId,orderLineId,
            orderLineId.toString(),BigDecimal.ZERO,quantity.negate(),issuer,subject,now);
    }
    public UUID getId(){return id;}
    public UUID getLocationId(){return locationId;}
    public UUID getSkuId(){return skuId;}
    public BigDecimal getDeltaOnHand(){return deltaOnHand;}
}
