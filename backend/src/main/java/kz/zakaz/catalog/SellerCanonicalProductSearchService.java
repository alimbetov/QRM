package kz.zakaz.catalog;

import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seller R2 usability: discover pre-existing canonical PHYSICAL products
 * without requiring a UUID copied from an administrator. This is deliberately
 * NOT the public listing feed; unpublished canonical products are visible
 * only after the controller validates an active catalog-editor membership.
 * This read never creates products or bypasses HCC publication rules.
 */
@Service
public class SellerCanonicalProductSearchService {
    public record ProductOption(String id,String name,String categoryCode,String categoryLabel) {}
    private final JdbcTemplate jdbc;

    public SellerCanonicalProductSearchService(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
    }

    @Transactional(readOnly=true)
    public List<ProductOption> search(String rawQuery) {
        if(rawQuery==null) throw new IllegalArgumentException("Search term required");
        String query=rawQuery.trim();
        if(query.length()<2 || query.length()>80 ||
           !query.matches("[\\p{L}\\p{N} .-]{2,80}"))
            throw new IllegalArgumentException("Invalid search term");
        // All wildcard characters are rejected by validation. The search is
        // bounded and parameterized; arbitrary SQL input cannot change it.
        return jdbc.query("""
            SELECT p.id::text,p.canonical_name,c.hcc_code,c.label
            FROM catalog_product p
            JOIN catalog_category c ON c.id=p.category_id
            WHERE c.active=true AND c.product_type='PHYSICAL'
              AND lower(p.canonical_name) LIKE ?
            ORDER BY lower(p.canonical_name), p.id
            LIMIT 20
            """,(rs,i)->new ProductOption(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getString(4)),
            "%"+query.toLowerCase(Locale.ROOT)+"%");
    }
}
