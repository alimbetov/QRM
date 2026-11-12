package kz.zakaz.deal;

import jakarta.persistence.*;
import java.util.*;

/** Server-managed active service area; bid quote must match demand destination. */
@Entity @Table(name="seller_delivery_area",uniqueConstraints=
    @UniqueConstraint(name="uk_seller_delivery_city",columnNames={"seller_id","city"}))
public class SellerDeliveryArea {
    @Id @Column(nullable=false) private UUID id;
    @Column(name="seller_id",nullable=false,updatable=false) private UUID sellerId;
    @Column(nullable=false,length=160,updatable=false) private String city;
    @Column(nullable=false) private boolean active;
    @Version private Long version;
    protected SellerDeliveryArea(){}
    public static SellerDeliveryArea allow(UUID sellerId,String city){
        Objects.requireNonNull(sellerId);
        if(city==null||city.isBlank()||city.length()>160)
            throw new IllegalArgumentException("City required");
        var area=new SellerDeliveryArea();area.id=UUID.randomUUID();area.sellerId=sellerId;
        area.city=city.trim();area.active=true;return area;
    }
}
