package kz.zakaz.deal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import kz.zakaz.intelligence.events.MlInvitationConsentService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * R2 read + write boundary for a REAL delivered and currently consented
 * invitation. Does not expose buyer identity, telephone or other demands.
 * Existing SellerBidSubmissionService remains the canonical quote writer.
 */
@Service
public class SellerInvitationBidWorkspaceService {
    public record SkuOption(UUID skuId,String code,String priceKzt,
                            Map<UUID,String> availableByOrigin) {}
    public record RequestedLine(UUID productId,String productName,String hccCode,
            String unit,String quantity,String maxUnitPrice,List<SkuOption> skus) {}
    public record OriginOption(UUID originLocationId,String city,String regionCode,
            String address,String type,boolean pickup,boolean delivery) {}
    public record Context(UUID invitationId,UUID demandId,String destinationCity,
            Instant expiresAt,int nextRevision,List<RequestedLine> lines,
            List<OriginOption> origins) {}
    public record BidLine(UUID productId,UUID skuId,BigDecimal quantity,BigDecimal unitPrice) {}
    public record BidRequest(int revision,Instant expiresAt,List<BidLine> lines,
            SellerBidQuoteSnapshot.Method method,UUID originLocationId,
            BigDecimal deliveryFee) {}
    public record Created(UUID bidId) {}

    private record DemandHeader(UUID demandId,String city,Instant expires) {}
    private record BaseLine(UUID productId,String name,String hcc,String unit,
                            BigDecimal quantity,BigDecimal maxPrice) {}
    private record Sku(UUID id,String code,BigDecimal price) {}

    private final JdbcTemplate jdbc;
    private final DealPersistenceService deals;
    private final SellerBidSubmissionService bids;
    private final MlInvitationConsentService consent;

    public SellerInvitationBidWorkspaceService(JdbcTemplate jdbc,
        DealPersistenceService deals,SellerBidSubmissionService bids,
        MlInvitationConsentService consent) {
        this.jdbc=jdbc;this.deals=deals;this.bids=bids;this.consent=consent;
    }

    /**
     * A delivered invitation alone is not enough: both opt-ins, live demand,
     * real seller membership and published catalog are verified on each access.
     * Read-only preview may become stale; POST validates again under DB locks.
     */
    @Transactional(readOnly=true)
    public Context preview(UUID sellerId,UUID invitationId) {
        return context(sellerId,invitationId,false);
    }

    @Transactional
    public Created submit(UUID sellerId,UUID invitationId,BidRequest request) {
        Objects.requireNonNull(request);
        // A buyer consent revoke locks the same consent row. Hold its lock
        // through SellerBid+Quote insert to prevent mid-request revocation.
        var ctx=context(sellerId,invitationId,true);
        if(request.revision()!=ctx.nextRevision() || request.revision()<1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Stale bid revision");
        Instant now=Instant.now();
        if(request.expiresAt()==null||!request.expiresAt().isAfter(now)||
            request.expiresAt().isAfter(now.plusSeconds(30L*86400))||
            request.expiresAt().isAfter(ctx.expiresAt()))
            throw new IllegalArgumentException("Bid expiry must be within demand expiry");
        if(request.lines()==null||request.lines().size()!=ctx.lines().size())
            throw new IllegalArgumentException("Full request basket is mandatory");
        if(request.method()==null||request.originLocationId()==null||
           request.deliveryFee()==null||request.deliveryFee().signum()<0||
           request.deliveryFee().scale()>2||request.deliveryFee().precision()>19)
            throw new IllegalArgumentException("Invalid quote");
        OriginOption origin=ctx.origins().stream()
            .filter(x->x.originLocationId().equals(request.originLocationId()))
            .findFirst().orElseThrow(()->new IllegalArgumentException("Unknown seller origin"));
        if((request.method()==SellerBidQuoteSnapshot.Method.PICKUP &&
                 (!origin.pickup()||request.deliveryFee().signum()!=0))||
           (request.method()==SellerBidQuoteSnapshot.Method.DELIVERY && !origin.delivery()))
            throw new IllegalArgumentException("Origin or method not eligible");

        var submitted=new HashMap<UUID,BidLine>();
        var lines=new ArrayList<SellerBid.BidLine>();
        for(var line:request.lines()) {
            if(line==null||line.productId()==null||line.skuId()==null||
                line.quantity()==null||line.unitPrice()==null||
                submitted.putIfAbsent(line.productId(),line)!=null)
                throw new IllegalArgumentException("Invalid or duplicate offered line");
        }
        for(var demanded:ctx.lines()) {
            BidLine offered=submitted.get(demanded.productId());
            if(offered==null||offered.quantity()==null||offered.unitPrice()==null||
               offered.quantity().compareTo(new BigDecimal(demanded.quantity()))!=0||
               offered.quantity().scale()>3||offered.unitPrice().signum()<0||
               offered.unitPrice().scale()>2||offered.unitPrice().precision()>19)
                throw new IllegalArgumentException("Offer differs from requested basket");
            SkuOption sku=demanded.skus().stream()
                .filter(s->s.skuId().equals(offered.skuId())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("SKU not eligible"));
            BigDecimal available=new BigDecimal(sku.availableByOrigin()
                .getOrDefault(origin.originLocationId(),"0"));
            if(available.compareTo(offered.quantity())<0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Insufficient current stock for bid origin");
            lines.add(new SellerBid.BidLine(offered.productId(),offered.skuId(),
                offered.quantity(),offered.unitPrice()));
        }
        // Recheck before write. Actual acceptance/confirmation always rechecks
        // authoritative stock, never assumes this preview reserved inventory.
        var bid=new SellerBid(UUID.randomUUID(),ctx.demandId(),sellerId,
            request.revision(),request.expiresAt(),lines);
        var quote=new SellerBidSubmissionService.QuoteRequest(
            request.method(),request.originLocationId(),request.deliveryFee());
        return new Created(bids.submit(sellerId,bid,quote,now));
    }

    private Context context(UUID seller,UUID invitation,boolean lockConsent) {
        Objects.requireNonNull(seller);Objects.requireNonNull(invitation);
        String locking=lockConsent?" FOR SHARE OF c,p":"";
        var invited=jdbc.query("""
            SELECT d.id,d.city,d.expires_at
            FROM ml_seller_invitation_inbox i
            JOIN ml_seller_invitation source ON source.id=i.invitation_id
              AND source.seller_id=i.seller_id AND source.demand_id=i.demand_id
              AND source.status='ELIGIBLE'
            JOIN buyer_demand_snapshot d ON d.id=i.demand_id
            JOIN ml_buyer_demand_invitation_consent c
              ON c.demand_id=d.id AND c.buyer_id=d.buyer_id AND c.enabled=true
            JOIN ml_seller_invitation_preference p
              ON p.seller_id=i.seller_id AND p.in_app_enabled=true
            WHERE i.seller_id=? AND i.invitation_id=?
              AND d.expires_at>clock_timestamp()
              AND NOT EXISTS (SELECT 1 FROM bid_acceptance a WHERE a.demand_id=d.id)
            """+locking,(rs,i)->new DemandHeader(rs.getObject(1,UUID.class),
                rs.getString(2),rs.getTimestamp(3).toInstant()),seller,invitation);
        if(invited.isEmpty())throw new NoSuchElementException("Invitation not available");
        var header=invited.getFirst();
        // The existing ML-R2 inbox is the source for live visibility, taxonomy,
        // seller preference, and delivery eligibility.
        if(consent.listSellerInbox(seller,100).stream()
            .noneMatch(x->x.invitationId().equals(invitation)))
            throw new NoSuchElementException("Invitation not currently visible");

        var requested=jdbc.query("""
            SELECT l.product_id,p.canonical_name,c.hcc_code,l.unit,
                l.quantity,l.max_unit_price
            FROM buyer_demand_line_snapshot l
            JOIN catalog_product p ON p.id=l.product_id
            JOIN catalog_category c ON c.id=p.category_id
            WHERE l.demand_id=? ORDER BY l.line_number
            """,(rs,i)->new BaseLine(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getString(3),rs.getString(4),rs.getBigDecimal(5),
                rs.getBigDecimal(6)),header.demandId());
        if(requested.isEmpty()||requested.size()>50)
            throw new IllegalStateException("Demand lines unavailable");
        boolean delivers=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM seller_delivery_area
              WHERE seller_id=? AND city=? AND active=true)
            """,Boolean.class,seller,header.city()));
        var origins=jdbc.query("""
            SELECT id,city,region_code,address_line,location_type,
                   public_pickup_enabled
            FROM inventory_location
            WHERE seller_id=? AND active=true ORDER BY id LIMIT 40
            """,(rs,i)->new OriginOption(rs.getObject(1,UUID.class),
                rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),
                rs.getBoolean(6),delivers),seller);
        var lines=new ArrayList<RequestedLine>();
        for(var request:requested) {
            if(request.unit()==null)
                throw new IllegalStateException("Untyped demand is not eligible");
            var matching=jdbc.query("""
                SELECT sku.id,sku.seller_sku_code,sku.public_unit_price_kzt
                FROM catalog_sku sku
                JOIN catalog_listing l ON l.id=sku.listing_id
                JOIN catalog_product product ON product.id=l.product_id
                JOIN catalog_category category ON category.id=product.category_id
                WHERE l.seller_id=? AND l.status='PUBLIC' AND category.active=true
                  AND l.product_id=? AND sku.sales_unit=?
                  AND sku.tracking_mode='TRACKED'
                ORDER BY sku.id LIMIT 20
                """,(rs,i)->new Sku(rs.getObject(1,UUID.class),
                    rs.getString(2),rs.getBigDecimal(3)),
                seller,request.productId(),request.unit());
            if(matching.isEmpty())throw new NoSuchElementException(
                "No matching seller SKU for full basket");
            var options=new ArrayList<SkuOption>();
            for(var sku:matching) {
                Map<UUID,String> byOrigin=new HashMap<>();
                jdbc.query("""
                    SELECT location_id,on_hand-reserved AS available
                    FROM inventory_balance
                    WHERE seller_id=? AND sku_id=?
                    """,rs->byOrigin.put(rs.getObject(1,UUID.class),
                        rs.getBigDecimal(2).toPlainString()),seller,sku.id());
                options.add(new SkuOption(sku.id(),sku.code(),
                    sku.price()==null?null:sku.price().toPlainString(),
                    Map.copyOf(byOrigin)));
            }
            lines.add(new RequestedLine(request.productId(),request.name(),
                request.hcc(),request.unit(),request.quantity().toPlainString(),
                request.maxPrice()==null?null:request.maxPrice().toPlainString(),
                List.copyOf(options)));
        }
        if(origins.stream().noneMatch(x->x.pickup()||x.delivery()))
            throw new NoSuchElementException("No active fulfillment origin");
        int next=jdbc.queryForObject("""
            SELECT COALESCE(MAX(revision),0)+1 FROM seller_bid_snapshot
            WHERE demand_id=? AND seller_id=?
            """,Integer.class,header.demandId(),seller);
        return new Context(invitation,header.demandId(),header.city(),
            header.expires(),next,List.copyOf(lines),List.copyOf(origins));
    }
}
