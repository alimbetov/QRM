package kz.zakaz.deal;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.*;

@Entity
@Table(name="seller_bid_line_snapshot",uniqueConstraints=
    @UniqueConstraint(name="uk_bid_line_product",columnNames={"bid_id","product_id"}))
public class SellerBidLineSnapshot {
    @Id @Column(nullable=false) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="bid_id",nullable=false,updatable=false) private SellerBidSnapshot bid;
    @Column(name="line_number",nullable=false,updatable=false) private int lineNumber;
    @Column(name="product_id",nullable=false,updatable=false) private UUID productId;
    @Column(name="sku_id",nullable=false,updatable=false) private UUID skuId;
    @Column(nullable=false,precision=19,scale=3,updatable=false) private BigDecimal quantity;
    @Column(name="unit_price",nullable=false,precision=19,scale=2,updatable=false) private BigDecimal unitPrice;
    protected SellerBidLineSnapshot(){}
    static SellerBidLineSnapshot capture(SellerBidSnapshot bid,int n,SellerBid.BidLine line){
        var e=new SellerBidLineSnapshot();
        e.id=UUID.randomUUID();e.bid=bid;e.lineNumber=n;e.productId=line.productId();
        e.skuId=line.skuId();e.quantity=line.quantity();e.unitPrice=line.unitPrice();
        return e;
    }
    SellerBid.BidLine toDomain(){
        return new SellerBid.BidLine(productId,skuId,quantity.stripTrailingZeros(),unitPrice);
    }
}
