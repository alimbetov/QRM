package kz.zakaz.order;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import kz.zakaz.domain.types.OrderStatus;

@Entity
@Table(name="customer_order",uniqueConstraints=@UniqueConstraint(name="uk_order_public_number",columnNames="public_number"))
public class CustomerOrder {
 @Id private UUID id;
 @Column(name="public_number",nullable=false,updatable=false,length=12) private String publicNumber;
 @Column(name="seller_id",nullable=false,updatable=false) private UUID sellerId;
 @Column(name="buyer_id",nullable=false,updatable=false) private UUID buyerId;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=40) private OrderStatus status;
 @Column(name="total_amount",nullable=false,precision=19,scale=2,updatable=false) private BigDecimal totalAmount;
 @Column(name="delivery_fee",nullable=false,precision=19,scale=2,updatable=false) private BigDecimal deliveryFee;
 @Column(name="accepted_bid_id",updatable=false) private UUID acceptedBidId;
 @Column(name="currency_code",nullable=false,length=3,updatable=false) private String currencyCode;
 @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
 @Version private Long version;
 @OneToMany(mappedBy="order",cascade=CascadeType.PERSIST,fetch=FetchType.LAZY)
 @OrderBy("lineNumber ASC") private List<OrderLine> lines=new ArrayList<>();
 protected CustomerOrder(){}
 public static CustomerOrder placed(UUID sellerId,UUID buyerId,String publicNumber,List<OrderLine.Draft> drafts){
     return newOrder(sellerId,buyerId,publicNumber,drafts,null,BigDecimal.ZERO,Instant.now());
 }
 public static CustomerOrder placedFromBid(UUID sellerId,UUID buyerId,String publicNumber,
                  List<OrderLine.Draft> drafts,UUID acceptedBidId,BigDecimal deliveryFee,Instant createdAt){
     Objects.requireNonNull(acceptedBidId);
     return newOrder(sellerId,buyerId,publicNumber,drafts,acceptedBidId,deliveryFee,createdAt);
 }
 private static CustomerOrder newOrder(UUID sellerId,UUID buyerId,String publicNumber,
       List<OrderLine.Draft> drafts,UUID acceptedBidId,BigDecimal deliveryFee,Instant createdAt){
  Objects.requireNonNull(sellerId);Objects.requireNonNull(buyerId);Objects.requireNonNull(createdAt);
  if(!OrderNumberGenerator.isValid(publicNumber))throw new IllegalArgumentException("Invalid number");
  if(drafts==null||drafts.isEmpty())throw new IllegalArgumentException("Empty order");
  if(deliveryFee==null||deliveryFee.signum()<0||deliveryFee.scale()>2)
      throw new IllegalArgumentException("Invalid delivery fee");
  CustomerOrder o=new CustomerOrder();o.id=UUID.randomUUID();o.sellerId=sellerId;o.buyerId=buyerId;
  o.publicNumber=publicNumber;o.acceptedBidId=acceptedBidId;o.deliveryFee=deliveryFee.setScale(2);
  o.status=OrderStatus.PLACED;o.createdAt=createdAt;o.currencyCode="KZT";
  BigDecimal sum=BigDecimal.ZERO;
  for(OrderLine.Draft d:drafts){
      OrderLine l=OrderLine.of(o,o.lines.size()+1,d);o.lines.add(l);sum=sum.add(l.getLineTotal());
  }
  o.totalAmount=sum.add(o.deliveryFee).setScale(2);return o;
 }
 public void confirm(){
  if(status!=OrderStatus.PLACED)throw new IllegalStateException("Order not PLACED");
  status=OrderStatus.CONFIRMED;
 }
 public UUID getId(){return id;}
 public String getPublicNumber(){return publicNumber;}
 public OrderStatus getStatus(){return status;}
 public BigDecimal getTotalAmount(){return totalAmount;}
 public BigDecimal getDeliveryFee(){return deliveryFee;}
 public UUID getAcceptedBidId(){return acceptedBidId;}
 public UUID getSellerId(){return sellerId;}
 public UUID getBuyerId(){return buyerId;}
 public List<OrderLine> getLines(){return Collections.unmodifiableList(lines);}
}
