package kz.zakaz.deal;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Immutable persisted snapshot of a buyer request. This is not a buyer HTTP
 * endpoint or a proof of buyer authentication; trusted caller required.
 */
@Entity
@Table(name="buyer_demand_snapshot")
public class BuyerDemandSnapshot {
    @Id @Column(nullable=false) private UUID id;
    @Column(name="buyer_id",nullable=false,updatable=false) private UUID buyerId;
    @Column(nullable=false,length=160,updatable=false) private String city;
    @Column(name="expires_at",nullable=false,updatable=false) private Instant expiresAt;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @OneToMany(mappedBy="demand",cascade=CascadeType.PERSIST,fetch=FetchType.LAZY)
    @OrderBy("lineNumber ASC") private List<BuyerDemandLineSnapshot> lines=new ArrayList<>();
    protected BuyerDemandSnapshot(){}
    public static BuyerDemandSnapshot capture(BuyerDemand d,Instant createdAt){
        Objects.requireNonNull(d);Objects.requireNonNull(createdAt);
        if(!d.isOpenAt(createdAt))throw new IllegalStateException("Demand already expired");
        var entity=new BuyerDemandSnapshot();
        entity.id=d.id();entity.buyerId=d.buyerId();entity.city=d.city();
        entity.expiresAt=d.expiresAt();entity.createdAt=createdAt;
        for(var line:d.lines())
            entity.lines.add(BuyerDemandLineSnapshot.capture(entity,entity.lines.size()+1,line));
        return entity;
    }
    public BuyerDemand toDomain(){
        var domainLines=lines.stream().map(BuyerDemandLineSnapshot::toDomain).toList();
        return new BuyerDemand(id,buyerId,city,expiresAt,domainLines);
    }
    public UUID getId(){return id;}
    public UUID getBuyerId(){return buyerId;}
}
