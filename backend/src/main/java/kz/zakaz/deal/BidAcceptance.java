package kz.zakaz.deal;

import jakarta.persistence.*;
import java.util.*;

/** Immutable agreement: exactly one buyer-selected bid revision per demand. */
@Entity @Table(name="bid_acceptance",uniqueConstraints={
    @UniqueConstraint(name="uk_acceptance_demand",columnNames="demand_id"),
    @UniqueConstraint(name="uk_acceptance_buyer_key",columnNames={"buyer_id","idempotency_key"}),
    @UniqueConstraint(name="uk_acceptance_order",columnNames="order_id")
})
public class BidAcceptance {
    @Id @Column(nullable=false) private UUID id;
    @Version private Long version;
    @Column(name="demand_id",nullable=false,updatable=false) private UUID demandId;
    @Column(name="bid_id",nullable=false,updatable=false) private UUID bidId;
    @Column(name="buyer_id",nullable=false,updatable=false) private UUID buyerId;
    @Column(name="idempotency_key",nullable=false,length=80,updatable=false) private String idempotencyKey;
    @Column(name="request_fingerprint",nullable=false,length=64,updatable=false)
    private String requestFingerprint;
    @Column(name="order_id",nullable=false,updatable=false) private UUID orderId;
    protected BidAcceptance(){}
    public static BidAcceptance accept(UUID buyerId,UUID demandId,UUID bidId,
                                      String key,String fingerprint,UUID orderId) {
        Objects.requireNonNull(buyerId);Objects.requireNonNull(demandId);
        Objects.requireNonNull(bidId);Objects.requireNonNull(orderId);
        if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,80}")
                || fingerprint==null || !fingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid idempotency key/fingerprint");
        var a=new BidAcceptance();a.id=UUID.randomUUID();a.buyerId=buyerId;a.demandId=demandId;
        a.bidId=bidId;a.idempotencyKey=key;a.requestFingerprint=fingerprint;a.orderId=orderId;
        return a;
    }
    public UUID getOrderId(){return orderId;}
    public String getRequestFingerprint(){return requestFingerprint;}
}
