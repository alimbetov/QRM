package kz.zakaz.intelligence.events;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ML-R2 gate shared by autonomous discovery, immutable decisions,
 * consumer projection and live seller inbox visibility.
 *
 * Same economic rules as the real SellerInvitationBidWorkspace:
 * complete immutable demand basket, TRACKED unit-compatible PUBLIC SKUs,
 * enough FREE stock for every line at ONE active seller origin,
 * and a real permitted delivery route or public pickup in demand city.
 *
 * This is prequalification only: actual bid/confirmation must recheck stock
 * and seller authorization under their own transactions. No HCC hard-
 * attribute demand schema exists yet; never claim attribute-level match.
 */
@Component
public final class MlSellerEligibilityPolicy {
    private final JdbcTemplate jdbc;
    public MlSellerEligibilityPolicy(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    private static String ref(String expression) {
        // Compile-time SQL aliases only. No request- or merchant-supplied SQL.
        if (!Set.of("?", "d.id", "d.city", "l.seller_id",
                    "i.seller_id", "inbox.seller_id").contains(expression))
            throw new IllegalArgumentException("Untrusted ML eligibility SQL expression");
        return expression;
    }

    /** Full basket is mandatory, maximum 50 lines as in the seller workspace. */
    static String fullCatalogBasket(String sellerExpression,String demandExpression) {
        String seller=ref(sellerExpression),demand=ref(demandExpression);
        return """
            (
              (SELECT COUNT(*) FROM buyer_demand_line_snapshot dl
               WHERE dl.demand_id=:DEMAND:) BETWEEN 1 AND 50
              AND NOT EXISTS (
                SELECT 1 FROM buyer_demand_line_snapshot needed
                WHERE needed.demand_id=:DEMAND:
                  AND (needed.unit IS NULL OR needed.unit IN ('SERVICE','LICENSE')
                    OR NOT EXISTS (
                      SELECT 1 FROM catalog_listing listing
                      JOIN catalog_product product ON product.id=listing.product_id
                      JOIN catalog_category category ON category.id=product.category_id
                      JOIN catalog_sku sku ON sku.listing_id=listing.id
                      WHERE listing.seller_id=:SELLER:
                        AND listing.product_id=needed.product_id
                        AND listing.status='PUBLIC' AND category.active=true
                        AND sku.tracking_mode='TRACKED'
                        AND sku.sales_unit=needed.unit))
              )
            )
            """.replace(":SELLER:",seller).replace(":DEMAND:",demand);
    }

    /** Full basket must be fulfillable from one origin; no cross-warehouse sum. */
    static String stockedOrigin(String sellerExpression,String demandExpression,
                                String cityExpression) {
        String seller=ref(sellerExpression),demand=ref(demandExpression),city=ref(cityExpression);
        return "(" + fullCatalogBasket(seller,demand) + " AND " + """
            EXISTS (
              SELECT 1 FROM inventory_location origin
              WHERE origin.seller_id=:SELLER: AND origin.active=true
                AND (
                  (origin.city=:CITY: AND origin.public_pickup_enabled=true)
                  OR EXISTS (
                    SELECT 1 FROM seller_delivery_area area
                    WHERE area.seller_id=origin.seller_id
                      AND area.city=:CITY: AND area.active=true)
                )
                AND NOT EXISTS (
                  SELECT 1 FROM buyer_demand_line_snapshot needed
                  WHERE needed.demand_id=:DEMAND:
                    AND NOT EXISTS (
                      SELECT 1 FROM catalog_listing listing
                      JOIN catalog_product product ON product.id=listing.product_id
                      JOIN catalog_category category ON category.id=product.category_id
                      JOIN catalog_sku sku ON sku.listing_id=listing.id
                      JOIN inventory_balance balance
                        ON balance.seller_id=origin.seller_id
                       AND balance.location_id=origin.id AND balance.sku_id=sku.id
                       AND balance.on_hand-balance.reserved>=needed.quantity
                      WHERE listing.seller_id=origin.seller_id
                        AND listing.product_id=needed.product_id
                        AND listing.status='PUBLIC' AND category.active=true
                        AND sku.tracking_mode='TRACKED'
                        AND sku.sales_unit=needed.unit
                    )
                )
            )
            """.replace(":SELLER:",seller).replace(":DEMAND:",demand)
               .replace(":CITY:",city) + ")";
    }

    public boolean hasFullCatalogBasket(UUID sellerId,UUID demandId) {
        Objects.requireNonNull(sellerId);Objects.requireNonNull(demandId);
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT "+fullCatalogBasket("?","d.id")+
            " FROM buyer_demand_snapshot d WHERE d.id=?",
            Boolean.class,sellerId,demandId));
    }

    public boolean hasUsableTerritory(UUID sellerId,String city) {
        Objects.requireNonNull(sellerId);Objects.requireNonNull(city);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT (
              EXISTS(SELECT 1 FROM seller_delivery_area area
                WHERE area.seller_id=? AND area.city=? AND area.active=true)
              OR EXISTS(SELECT 1 FROM inventory_location origin
                WHERE origin.seller_id=? AND origin.city=? AND origin.active=true
                  AND origin.public_pickup_enabled=true)
            )
            """,Boolean.class,sellerId,city,sellerId,city));
    }

    public boolean hasStockedOrigin(UUID sellerId,UUID demandId) {
        Objects.requireNonNull(sellerId);Objects.requireNonNull(demandId);
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT "+stockedOrigin("?","d.id","d.city")+
            " FROM buyer_demand_snapshot d WHERE d.id=?",
            Boolean.class,sellerId,demandId));
    }
}
