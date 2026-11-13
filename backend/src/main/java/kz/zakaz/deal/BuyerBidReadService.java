package kz.zakaz.deal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import kz.zakaz.security.BuyerIdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Buyer-scoped, immutable bid quote projection; no seller contact or warehouse data. */
@Service
public class BuyerBidReadService {
    public record BidLine(UUID skuId, UUID productId, String quantity,
                          String unit, String unitPriceKzt) {}
    public record Bid(UUID bidId, UUID sellerId, int revision, Instant expiresAt,
                      String method, String totalKzt, String deliveryFeeKzt,
                      List<BidLine> lines) {}
    public record View(UUID demandId, UUID acceptedOrderId, List<Bid> bids) {}
    private final BuyerIdentityService identities;
    private final JdbcTemplate jdbc;

    public BuyerBidReadService(BuyerIdentityService identities, JdbcTemplate jdbc) {
        this.identities=identities;this.jdbc=jdbc;
    }

    private static String money(BigDecimal v){return v==null?null:v.toPlainString();}

    @Transactional(readOnly=true)
    public View read(JwtAuthenticationToken actor, UUID demandId){
        UUID buyerId=identities.requireBuyer(actor);
        var owner=jdbc.query("SELECT buyer_id FROM buyer_demand_snapshot WHERE id=?",
            (rs,i)->(UUID)rs.getObject(1),demandId);
        if(owner.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(!owner.getFirst().equals(buyerId))
            throw new AccessDeniedException("Buyer does not own demand");

        var orderIds=jdbc.query("SELECT order_id FROM bid_acceptance WHERE demand_id=?",
            (rs,i)->(UUID)rs.getObject(1),demandId);
        UUID accepted=orderIds.isEmpty()?null:orderIds.getFirst();
        var bids=jdbc.query("""
            SELECT b.id AS bid_id,b.seller_id,b.revision,b.expires_at,
                   q.method,q.all_in_amount,q.delivery_fee
            FROM seller_bid_snapshot b
            JOIN bid_quote_snapshot q ON q.bid_id=b.id
            JOIN buyer_demand_snapshot d ON d.id=b.demand_id
            WHERE b.demand_id=?
              AND b.expires_at>now() AND d.expires_at>now()
              AND NOT EXISTS (
                SELECT 1 FROM seller_bid_snapshot newer
                WHERE newer.demand_id=b.demand_id AND newer.seller_id=b.seller_id
                  AND newer.revision>b.revision
              )
            ORDER BY q.all_in_amount ASC, b.submitted_at ASC, b.id ASC
            LIMIT 50
            """,(rs,i)->new Bid((UUID)rs.getObject("bid_id"),
                (UUID)rs.getObject("seller_id"),rs.getInt("revision"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getString("method"),money(rs.getBigDecimal("all_in_amount")),
                money(rs.getBigDecimal("delivery_fee")),List.of()),demandId);
        if(bids.isEmpty())return new View(demandId,accepted,List.of());
        var bidIds=bids.stream().map(Bid::bidId).toList();
        var placeholders=String.join(",",Collections.nCopies(bidIds.size(),"?"));
        var lines=jdbc.query("""
            SELECT bl.bid_id,bl.sku_id,bl.product_id,bl.quantity,bl.unit_price,s.sales_unit
            FROM seller_bid_line_snapshot bl
            JOIN catalog_sku s ON s.id=bl.sku_id
            WHERE bl.bid_id IN ("""+placeholders+"""
            ) ORDER BY bl.bid_id,bl.line_number
            """,(rs,i)->new Object[]{
              (UUID)rs.getObject("bid_id"),
              new BidLine((UUID)rs.getObject("sku_id"),(UUID)rs.getObject("product_id"),
                money(rs.getBigDecimal("quantity")),rs.getString("sales_unit"),
                money(rs.getBigDecimal("unit_price")))
            },bidIds.toArray());
        var byBid=new HashMap<UUID,List<BidLine>>();
        for(var item:lines) {
            byBid.computeIfAbsent((UUID)item[0],k->new ArrayList<>()).add((BidLine)item[1]);
        }
        return new View(demandId,accepted,bids.stream().map(b->
            new Bid(b.bidId(),b.sellerId(),b.revision(),b.expiresAt(),b.method(),
                    b.totalKzt(),b.deliveryFeeKzt(),
                    List.copyOf(byBid.getOrDefault(b.bidId(),List.of())))).toList());
    }
}
