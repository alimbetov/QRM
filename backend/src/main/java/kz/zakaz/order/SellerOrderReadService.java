package kz.zakaz.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import kz.zakaz.security.SellerIdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Seller-scoped order projection. No buyer identity, email or phone leaves this endpoint. */
@Service
public class SellerOrderReadService {
    public record Line(UUID skuId,String quantity,String unit,String lineTotal) {}
    public record Order(UUID orderId,String publicNumber,String status,
                        String totalAmount,String deliveryFee,String currency,
                        Instant createdAt,List<Line> lines) {}
    public record Page(int page,int size,long total,List<Order> items) {}
    private record Summary(UUID id,String number,String status,String total,
                           String fee,String currency,Instant createdAt) {}
    private final SellerIdentityService identity;
    private final JdbcTemplate jdbc;

    public SellerOrderReadService(SellerIdentityService identity,JdbcTemplate jdbc) {
        this.identity=identity;this.jdbc=jdbc;
    }

    @Transactional(readOnly=true)
    public Page list(JwtAuthenticationToken actor,UUID sellerId,int page,int size,String status) {
        UUID verified=identity.requireSellerAdmin(actor,sellerId);
        if(page<0||page>1000||size<1||size>20)
            throw new IllegalArgumentException("Invalid order pagination");
        boolean all=status==null||"ALL".equals(status);
        if(!all) {
            try { kz.zakaz.domain.types.OrderStatus.valueOf(status); }
            catch(IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid order status");
            }
        }
        long total=Optional.ofNullable(jdbc.queryForObject(
            "SELECT count(*) FROM customer_order WHERE seller_id=?"+
                (all?"":" AND status=?"),Long.class,
            all?new Object[]{verified}:new Object[]{verified,status}
        )).orElse(0L);
        var rows=jdbc.query("""
            SELECT id,public_number,status,total_amount,delivery_fee,
                   currency_code,created_at
            FROM customer_order WHERE seller_id=?
            """+(all?"":" AND status=?")+"""
             ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
            """,(rs,i)->new Summary(
                rs.getObject("id",UUID.class),rs.getString("public_number"),
                rs.getString("status"),rs.getBigDecimal("total_amount").toPlainString(),
                rs.getBigDecimal("delivery_fee").toPlainString(),
                rs.getString("currency_code"),rs.getTimestamp("created_at").toInstant()),
            all?new Object[]{verified,size,page*size}:new Object[]{verified,status,size,page*size});
        if(rows.isEmpty())return new Page(page,size,total,List.of());
        var ids=rows.stream().map(Summary::id).toList();
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        var lineRows=jdbc.query("""
            SELECT order_id,sku_id,quantity,unit,line_total FROM order_line
            WHERE order_id IN ("""+placeholders+"""
            ) ORDER BY order_id,line_number
            """,(rs,i)->new Object[]{
                rs.getObject("order_id",UUID.class),
                new Line(rs.getObject("sku_id",UUID.class),
                    rs.getBigDecimal("quantity").toPlainString(),
                    rs.getString("unit"),rs.getBigDecimal("line_total").toPlainString())
            },ids.toArray());
        var byOrder=new HashMap<UUID,List<Line>>();
        for(var entry:lineRows)
            byOrder.computeIfAbsent((UUID)entry[0],key->new ArrayList<>())
                .add((Line)entry[1]);
        return new Page(page,size,total,rows.stream().map(o->new Order(
            o.id(),o.number(),o.status(),o.total(),o.fee(),o.currency(),o.createdAt(),
            List.copyOf(byOrder.getOrDefault(o.id(),List.of())))).toList());
    }
}
