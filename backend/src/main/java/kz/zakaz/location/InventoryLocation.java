package kz.zakaz.location;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import kz.zakaz.domain.types.LocationType;

@Entity
@Table(name="inventory_location",uniqueConstraints=@UniqueConstraint(name="uk_inventory_location_seller_code",columnNames={"seller_id","code"}))
public class InventoryLocation {
 @Id private UUID id;
 @Column(name="seller_id",nullable=false,updatable=false) private UUID sellerId;
 @Column(nullable=false,length=40) private String code;
 @Column(nullable=false,length=160) private String name;
 @Column(name="region_code",nullable=false,length=32) private String regionCode;
 @Column(name="region_name",nullable=false,length=160) private String regionName;
 @Column(nullable=false,length=160) private String city;
 @Column(name="address_line",nullable=false,length=400) private String addressLine;
 @Enumerated(EnumType.STRING)@Column(name="location_type",nullable=false,length=30) private LocationType locationType;
 @Column(name="public_pickup_enabled",nullable=false) private boolean publicPickupEnabled;
 @Column(precision=10,scale=7) private BigDecimal latitude;
 @Column(precision=10,scale=7) private BigDecimal longitude;
 @Column(nullable=false)private boolean active;
 @Version private long version;
 protected InventoryLocation(){}
 public static InventoryLocation create(UUID sellerId,String code,String name,String regionCode,String regionName,
                                            String city,String addressLine,LocationType type,GeoPoint point,boolean publicPickup){
  Objects.requireNonNull(sellerId); Objects.requireNonNull(type);
  InventoryLocation l=new InventoryLocation();l.id=UUID.randomUUID();l.sellerId=sellerId;
  l.code=required(code);l.name=required(name);l.regionCode=required(regionCode);l.regionName=required(regionName);
  l.city=required(city);l.addressLine=required(addressLine);l.locationType=type;
  if(publicPickup && type!=LocationType.PICKUP_POINT && type!=LocationType.SHOP && type!=LocationType.SERVICE_POINT)
   throw new IllegalArgumentException("Private warehouse cannot become public pickup");
  l.publicPickupEnabled=publicPickup;l.active=true;l.setPoint(point);return l;
 }
 private static String required(String s){if(s==null||s.isBlank())throw new IllegalArgumentException("Required location field");return s.trim();}
 public void setPoint(GeoPoint p){this.latitude=p==null?null:p.latitude();this.longitude=p==null?null:p.longitude();}
 @PrePersist @PreUpdate void validate(){
  if((latitude==null)!=(longitude==null))throw new IllegalStateException("Incomplete geo point");
  if(latitude!=null)new GeoPoint(latitude,longitude);
 }
 public UUID getId(){return id;}
 public UUID getSellerId(){return sellerId;}
 public String getCity(){return city;}
 public String getAddressLine(){return addressLine;}
 public boolean isPublicPickupEnabled(){return publicPickupEnabled;}
 public GeoPoint getPoint(){return latitude==null?null:new GeoPoint(latitude,longitude);}
 public LocationType getLocationType(){return locationType;}
}
