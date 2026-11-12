package kz.zakaz.order;
import jakarta.persistence.*;
import java.math.*;
import java.util.*;
import kz.zakaz.domain.types.MeasurementUnit;
@Entity
@Table(name="order_line",uniqueConstraints=@UniqueConstraint(name="uk_order_line_number",columnNames={"order_id","line_number"}))
public class OrderLine{
 @Id private UUID id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="order_id",nullable=false,updatable=false) private CustomerOrder order;
 @Column(name="line_number",nullable=false,updatable=false)private int lineNumber;
 @Column(name="sku_id",nullable=false,updatable=false)private UUID skuId;
 @Column(nullable=false,precision=19,scale=3,updatable=false)private BigDecimal quantity;
 @Enumerated(EnumType.STRING)@Column(nullable=false,updatable=false,length=20)private MeasurementUnit unit;
 @Column(name="unit_price",nullable=false,precision=19,scale=2,updatable=false)private BigDecimal unitPrice;
 @Column(name="line_total",nullable=false,precision=19,scale=2,updatable=false)private BigDecimal lineTotal;
 @Column(name="origin_city_snapshot",length=160,updatable=false)private String originCitySnapshot;
 @Column(name="origin_region_code_snapshot",length=32,updatable=false)private String originRegionCodeSnapshot;
 protected OrderLine(){}
 public record Draft(UUID skuId,BigDecimal quantity,MeasurementUnit unit,BigDecimal unitPrice,String city,String region){}
 static OrderLine of(CustomerOrder order,int num,Draft d){
  if(d==null||d.skuId()==null||d.quantity()==null||d.unit()==null||d.unitPrice()==null)throw new IllegalArgumentException("Incomplete line");
  if(d.quantity().signum()<=0||d.quantity().scale()>3)throw new IllegalArgumentException("Invalid quantity");
  if((d.unit()==MeasurementUnit.PCS||d.unit()==MeasurementUnit.LICENSE)&&d.quantity().stripTrailingZeros().scale()>0)throw new IllegalArgumentException("Fractional discrete item");
  if(d.unitPrice().signum()<0||d.unitPrice().scale()>2)throw new IllegalArgumentException("Invalid unit price");
  OrderLine l=new OrderLine();l.id=UUID.randomUUID();l.order=order;l.lineNumber=num;l.skuId=d.skuId();
  l.quantity=d.quantity();l.unit=d.unit();l.unitPrice=d.unitPrice().setScale(2);
  l.lineTotal=l.unitPrice.multiply(l.quantity).setScale(2,RoundingMode.HALF_UP);
  l.originCitySnapshot=d.city();l.originRegionCodeSnapshot=d.region();return l;
 }
 public UUID getSkuId(){return skuId;}
 public BigDecimal getLineTotal(){return lineTotal;}
 public String getOriginCitySnapshot(){return originCitySnapshot;}
}