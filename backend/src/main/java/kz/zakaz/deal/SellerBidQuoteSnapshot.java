package kz.zakaz.deal;

import jakarta.persistence.*;
import java.math.*;
import java.util.*;

/**
 * Terms bound exactly once to a bid revision. Only an authenticated seller may
 * originate them; source city/region are copied from authoritative location.
 */
@Entity @Table(name="bid_quote_snapshot")
public class SellerBidQuoteSnapshot {
    public enum Method { PICKUP, DELIVERY }
    @Id @Column(name="bid_id",nullable=false) private UUID bidId;
    @Version private Long version;
    @Enumerated(EnumType.STRING) @Column(name="method",length=16,nullable=false,updatable=false)
    private Method method;
    @Column(name="origin_location_id",nullable=false,updatable=false) private UUID originLocationId;
    @Column(name="origin_city",nullable=false,length=160,updatable=false) private String originCity;
    @Column(name="origin_region_code",nullable=false,length=32,updatable=false) private String originRegionCode;
    @Column(name="destination_city",nullable=false,length=160,updatable=false) private String destinationCity;
    @Column(name="currency_code",nullable=false,length=3,updatable=false) private String currencyCode;
    @Column(name="delivery_fee",nullable=false,precision=19,scale=2,updatable=false)
    private BigDecimal deliveryFee;
    @Column(name="all_in_amount",nullable=false,precision=19,scale=2,updatable=false)
    private BigDecimal allInAmount;
    protected SellerBidQuoteSnapshot(){}
    public static SellerBidQuoteSnapshot capture(UUID bidId,Method method,UUID locationId,
                            String city,String region,String destination,
                            BigDecimal merchandiseTotal,BigDecimal deliveryFee) {
        Objects.requireNonNull(bidId);Objects.requireNonNull(method);Objects.requireNonNull(locationId);
        if(city==null||city.isBlank()||region==null||region.isBlank()
                || destination==null||destination.isBlank())
            throw new IllegalArgumentException("Incomplete fulfillment geography");
        if(merchandiseTotal==null||merchandiseTotal.signum()<0 || merchandiseTotal.scale()>2
                || deliveryFee==null||deliveryFee.signum()<0||deliveryFee.scale()>2)
            throw new IllegalArgumentException("Invalid quote");
        if(method==Method.PICKUP && deliveryFee.signum()!=0)
            throw new IllegalArgumentException("Pickup cannot charge delivery fee");
        var s=new SellerBidQuoteSnapshot();s.bidId=bidId;s.method=method;
        s.originLocationId=locationId;s.originCity=city;s.originRegionCode=region;
        s.destinationCity=destination;s.deliveryFee=deliveryFee.setScale(2);
        s.currencyCode="KZT";s.allInAmount=merchandiseTotal.add(s.deliveryFee).setScale(2);
        return s;
    }
    public UUID getBidId(){return bidId;}
    public UUID getOriginLocationId(){return originLocationId;}
    public BigDecimal getDeliveryFee(){return deliveryFee;}
    public BigDecimal getAllInAmount(){return allInAmount;}
    public String getOriginCity(){return originCity;}
    public String getOriginRegionCode(){return originRegionCode;}
    public String getDestinationCity(){return destinationCity;}
    public Method getMethod(){return method;}
}
