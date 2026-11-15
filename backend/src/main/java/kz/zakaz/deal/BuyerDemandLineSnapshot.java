package kz.zakaz.deal;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.*;
import kz.zakaz.domain.types.MeasurementUnit;

@Entity
@Table(name="buyer_demand_line_snapshot",uniqueConstraints=
    @UniqueConstraint(name="uk_demand_line_product",columnNames={"demand_id","product_id"}))
public class BuyerDemandLineSnapshot {
    @Id @Column(nullable=false) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="demand_id",nullable=false,updatable=false) private BuyerDemandSnapshot demand;
    @Column(name="line_number",nullable=false,updatable=false) private int lineNumber;
    @Column(name="product_id",nullable=false,updatable=false) private UUID productId;
    @Column(nullable=false,precision=19,scale=3,updatable=false) private BigDecimal quantity;
    @Column(name="max_unit_price",precision=19,scale=2,updatable=false) private BigDecimal maxUnitPrice;
    @Enumerated(EnumType.STRING) @Column(name="unit",length=20,updatable=false)
    private MeasurementUnit unit;
    protected BuyerDemandLineSnapshot(){}
    static BuyerDemandLineSnapshot capture(BuyerDemandSnapshot parent,int lineNumber,BuyerDemand.DemandLine d){
        var e=new BuyerDemandLineSnapshot();e.id=UUID.randomUUID();e.demand=parent;e.lineNumber=lineNumber;
        e.productId=d.productId();e.quantity=d.quantity();e.maxUnitPrice=d.maxUnitPrice();e.unit=d.unit();
        return e;
    }
    BuyerDemand.DemandLine toDomain(){
        return new BuyerDemand.DemandLine(productId,quantity.stripTrailingZeros(),maxUnitPrice,unit);
    }
}
