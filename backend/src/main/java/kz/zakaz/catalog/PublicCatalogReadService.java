package kz.zakaz.catalog;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounded read-only projection. Public listings only, requiring a published
 * QR link; never serializes JPA entities or inventory/merchant private data.
 * JDBC projection avoids one lazy-query per card and uncontrolled fetch joins.
 */
@Service
public class PublicCatalogReadService {
    public record Variant(UUID id, String code, String unit, String priceKzt) {}
    public record Item(UUID listingId, UUID sellerId, String qrToken,
                       String title, String category, String categoryCode,
                       String productType, List<Variant> variants) {}
    public record CatalogPage(int page, int size, long total, List<Item> items) {}
    public record CategoryOption(String code, String label, String productType) {}

    private final NamedParameterJdbcTemplate jdbc;
    public PublicCatalogReadService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc=jdbc;
    }

    private static final String FROM_PUBLIC = """
        FROM catalog_listing l
        JOIN catalog_product p ON p.id=l.product_id
        JOIN catalog_category c ON c.id=p.category_id
        JOIN qr_public_link q ON q.listing_id=l.id
        WHERE l.status='PUBLIC' AND c.active=true
        """;

    @Transactional(readOnly=true)
    public CatalogPage search(String query, String categoryCode, int page, int size) {
        if (page<0 || page>1000 || size<1 || size>24)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page or size");
        if (query!=null && (query.length()>80 ||
                !query.matches("[\\p{L}\\p{N} .-]*")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid search term");
        if (categoryCode!=null && !categoryCode.matches("[0-9]{20}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid category code");
        String term=query==null?null:query.trim().toLowerCase(Locale.ROOT);
        var filters=new StringBuilder(FROM_PUBLIC);
        var params=new MapSqlParameterSource()
            .addValue("limit",size)
            .addValue("offset",page*size);
        if(categoryCode!=null){
            filters.append(" AND c.hcc_code=:categoryCode ");
            params.addValue("categoryCode",categoryCode);
        }
        if(term!=null&&!term.isEmpty()){
            filters.append(" AND lower(p.canonical_name) LIKE :search ");
            params.addValue("search","%"+term+"%");
        }
        Long count=jdbc.queryForObject("SELECT count(*) "+filters,params,Long.class);
        var rows=jdbc.query("""
            SELECT l.id, l.seller_id, q.token, p.canonical_name,
                   c.label AS category, c.hcc_code, c.product_type
            """+filters+"""
            ORDER BY p.canonical_name, l.id
            LIMIT :limit OFFSET :offset
            """,params,(rs,i)->new Item(
                (UUID)rs.getObject("id"),(UUID)rs.getObject("seller_id"),
                rs.getString("token"),rs.getString("canonical_name"),
                rs.getString("category"),rs.getString("hcc_code"),
                rs.getString("product_type"),List.of()));
        if(rows.isEmpty())return new CatalogPage(page,size,count==null?0:count,List.of());
        var ids=rows.stream().map(Item::listingId).toList();
        var variants=new HashMap<UUID,List<Variant>>();
        jdbc.query("""
            SELECT listing_id, id, seller_sku_code, sales_unit, public_unit_price_kzt
            FROM catalog_sku
            WHERE listing_id IN (:ids)
            ORDER BY listing_id, seller_sku_code, id
            """,new MapSqlParameterSource("ids",ids),rs->{
                UUID listingId=(UUID)rs.getObject("listing_id");
                BigDecimal price=rs.getBigDecimal("public_unit_price_kzt");
                variants.computeIfAbsent(listingId,k->new ArrayList<>())
                    .add(new Variant((UUID)rs.getObject("id"),rs.getString("seller_sku_code"),
                        rs.getString("sales_unit"),price==null?null:price.toPlainString()));
            });
        var items=rows.stream().map(r->new Item(r.listingId(),r.sellerId(),r.qrToken(),
            r.title(),r.category(),r.categoryCode(),r.productType(),
            List.copyOf(variants.getOrDefault(r.listingId(),List.of())))).toList();
        return new CatalogPage(page,size,count==null?0:count,items);
    }

    @Transactional(readOnly=true)
    public List<CategoryOption> categories(){
        return jdbc.query("""
            SELECT DISTINCT c.hcc_code, c.label, c.product_type
            FROM catalog_category c
            JOIN catalog_product p ON p.category_id=c.id
            JOIN catalog_listing l ON l.product_id=p.id
            JOIN qr_public_link q ON q.listing_id=l.id
            WHERE c.active=true AND l.status='PUBLIC'
            ORDER BY c.label,c.hcc_code
            LIMIT 100
            """,new MapSqlParameterSource(),
            (rs,i)->new CategoryOption(rs.getString(1),rs.getString(2),rs.getString(3)));
    }
}
