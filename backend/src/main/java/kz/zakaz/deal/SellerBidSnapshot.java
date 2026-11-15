package kz.zakaz.deal;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

/**
 * One immutable accepted-for-review bid revision; multiple revisions are
 * preserved and never overwritten. This entity does NOT create an order.
 */
@Entity
@Table(name="seller_bid_snapshot",uniqueConstraints=
    @UniqueConstraint(name="uk_bid_demand_seller_revision",columnNames={"demand_id","seller_id","revision"}))
public class SellerBidSnapshot {
    @Id @Column(nullable=false) private UUID id;
    @Version private Long version;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="demand_id",nullable=false,updatable=false) private BuyerDemandSnapshot demand;
    @Column(name="seller_id",nullable=false,updatable=false) private UUID sellerId;
    @Column(nullable=false,updatable=false) private int revision;
    @Column(name="expires_at",nullable=false,updatable=false) private Instant expiresAt;
    @Column(name="submitted_at",nullable=false,updatable=false) private Instant submittedAt;
    @OneToMany(mappedBy="bid",cascade=CascadeType.PERSIST,fetch=FetchType.LAZY)
    @OrderBy("lineNumber ASC") private List<SellerBidLineSnapshot> lines=new ArrayList<>();
    protected SellerBidSnapshot(){}
    public static SellerBidSnapshot capture(BuyerDemandSnapshot demand,SellerBid bid,Instant now){
        Objects.requireNonNull(demand);Objects.requireNonNull(bid);Objects.requireNonNull(now);
        var e=new SellerBidSnapshot();
        e.id=bid.id();e.demand=demand;e.sellerId=bid.sellerId();
        e.revision=bid.revision();e.expiresAt=bid.expiresAt();e.submittedAt=now;
        for(var l:bid.lines())e.lines.add(SellerBidLineSnapshot.capture(e,e.lines.size()+1,l));
        return e;
    }
    public SellerBid toDomain(){
        return new SellerBid(id,demand.getId(),sellerId,revision,expiresAt,
                             lines.stream().map(SellerBidLineSnapshot::toDomain).toList());
    }
    public UUID getId(){return id;}
    public UUID getDemandId(){return demand.getId();}
    public UUID getSellerId(){return sellerId;}
    public int getRevision(){return revision;}
}
