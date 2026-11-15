package kz.zakaz.catalog;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * SEARCH-S1: PostgreSQL-native public discovery with live publication checks.
 * No Elasticsearch, no speculative category links, no inventory or buyer PII.
 * The read path does not trust search metadata for authorization.
 */
@Service
@ConditionalOnProperty(name="zakaz.search.http.enabled",havingValue="true")
public class PublicSearchReadService {
    public record SearchPage(List<PublicCatalogReadService.Item> items,
                             String nextCursor,boolean hasMore){}
    public record Suggestion(String term,String locale,String categoryCode,
                             String relationType){}

    private record SearchRow(PublicCatalogReadService.Item item,int bucket,String sortTitle) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final SearchCursorCodec cursors;

    public PublicSearchReadService(NamedParameterJdbcTemplate jdbc,SearchCursorCodec cursors) {
        this.jdbc=jdbc;
        this.cursors=cursors;
    }

    private static final String SEARCH_SQL = """
        WITH RECURSIVE category_filter(category_id) AS (
            SELECT id FROM catalog_category
            WHERE hcc_code=:categoryCode AND active=true
            UNION
            SELECT child.id
            FROM catalog_category child
            JOIN category_filter f ON child.parent_id=f.category_id
            WHERE child.active=true
        ), matched_categories(category_id,relation_type) AS (
            SELECT tc.category_id,tc.relation_type
            FROM catalog_search_term st
            JOIN catalog_term_category tc ON tc.term_id=st.id
            JOIN catalog_category origin ON origin.id=tc.category_id
            WHERE st.status='ACTIVE' AND origin.active=true
              AND tc.relation_type IN ('SYNONYM','CATEGORY_TERM','SERVICE_INTENT')
              AND (st.normalized_term=:term OR (st.normalized_term % :term\n                  AND similarity(st.normalized_term,:term)>=0.64))
            UNION
            SELECT child.id,mc.relation_type
            FROM catalog_category child
            JOIN matched_categories mc ON child.parent_id=mc.category_id
            WHERE child.active=true
        ), ranked AS (
            SELECT l.id AS listing_id,p.id AS product_id,l.seller_id,q.token,
                   p.canonical_name,c.label AS category,c.hcc_code,c.product_type,
                   lower(p.canonical_name) AS sort_title,
                   CASE
                       WHEN :term<>'' AND lower(p.canonical_name)=:term THEN 0
                       WHEN :term<>'' AND lower(p.canonical_name) LIKE :contains THEN 1
                       WHEN :term<>'' AND to_tsvector('simple',lower(p.canonical_name))
                           @@ plainto_tsquery('simple',:term) THEN 2
                       WHEN EXISTS (
                           SELECT 1 FROM matched_categories m
                           WHERE m.category_id=c.id
                             AND (m.relation_type<>'SERVICE_INTENT'
                                  OR c.product_type='SERVICE')
                       ) THEN 3
                       ELSE 4
                   END AS bucket
            FROM catalog_listing l
            JOIN catalog_product p ON p.id=l.product_id
            JOIN catalog_category c ON c.id=p.category_id
            JOIN qr_public_link q ON q.listing_id=l.id
            WHERE l.status='PUBLIC' AND c.active=true
              AND (:categoryCode='' OR c.id IN (SELECT category_id FROM category_filter))
              AND (:productType='' OR c.product_type=:productType)
              AND (
                  :term=''
                  OR lower(p.canonical_name) LIKE :contains
                  OR to_tsvector('simple',lower(p.canonical_name))
                     @@ plainto_tsquery('simple',:term)
                  OR (lower(p.canonical_name) % :term\n                      AND similarity(lower(p.canonical_name),:term)>=0.55)
                  OR EXISTS (
                      SELECT 1 FROM matched_categories m
                      WHERE m.category_id=c.id
                        AND (m.relation_type<>'SERVICE_INTENT'
                             OR c.product_type='SERVICE')
                  )
              )
        )
        SELECT listing_id,product_id,seller_id,token,canonical_name,category,
               hcc_code,product_type,sort_title,bucket
        FROM ranked
        WHERE 1=1
        """;

    /**
     * One Listing is one result even when it contains multiple SKUs.
     * Keyset: (match bucket, lower title, listing UUID), never OFFSET.
     */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=5)
    public SearchPage search(String raw,String categoryCode,String productType,
                             int limit,String cursor) {
        return find(normalize(raw),categoryCode,productType,limit,cursor);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=5)
    public SearchPage browse(String categoryCode,String productType,int limit,String cursor) {
        return find("",categoryCode,productType,limit,cursor);
    }

    private SearchPage find(String term,String categoryCode,String productType,
                             int limit,String cursor) {
        String category=validateCategory(categoryCode);
        String kind=validateType(productType);
        if(limit<1||limit>48)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid search limit");

        Long revision=jdbc.queryForObject(
            "SELECT revision FROM catalog_search_dictionary_revision WHERE id=1",
            new MapSqlParameterSource(),Long.class);
        if(revision==null) throw new IllegalStateException("Search dictionary revision missing");
        SearchCursorCodec.Position last=cursors.decode(cursor,term,category,kind,revision);

        String after=last==null?"":"""
            AND (bucket>:lastBucket
               OR (bucket=:lastBucket AND sort_title>:lastTitle)
               OR (bucket=:lastBucket AND sort_title=:lastTitle AND listing_id>:lastId))
            """;
        var args=new MapSqlParameterSource()
            .addValue("term",term)
            .addValue("contains","%"+term+"%")
            .addValue("categoryCode",category)
            .addValue("productType",kind)
            .addValue("limit",limit+1);
        if(last!=null) {
            args.addValue("lastBucket",last.bucket())
                .addValue("lastTitle",last.sortTitle())
                .addValue("lastId",last.listingId());
        }
        var found=jdbc.query(SEARCH_SQL+after+"""
            ORDER BY bucket ASC,sort_title ASC,listing_id ASC
            LIMIT :limit
            """,args,(rs,i)->new SearchRow(
                new PublicCatalogReadService.Item(
                    rs.getObject("listing_id",UUID.class),
                    rs.getObject("product_id",UUID.class),
                    rs.getObject("seller_id",UUID.class),
                    rs.getString("token"),
                    rs.getString("canonical_name"),
                    rs.getString("category"),
                    rs.getString("hcc_code"),
                    rs.getString("product_type"),List.of()),
                rs.getInt("bucket"),rs.getString("sort_title")));

        boolean hasMore=found.size()>limit;
        var selected=hasMore?found.subList(0,limit):found;
        if(selected.isEmpty()) return new SearchPage(List.of(),null,false);

        var listingIds=selected.stream().map(r->r.item().listingId()).toList();
        var variants=new HashMap<UUID,List<PublicCatalogReadService.Variant>>();
        jdbc.query("""
            SELECT listing_id,id,seller_sku_code,sales_unit,public_unit_price_kzt
            FROM catalog_sku WHERE listing_id IN (:ids)
            ORDER BY listing_id,seller_sku_code,id
            """,new MapSqlParameterSource("ids",listingIds),rs->{
            UUID listingId=rs.getObject("listing_id",UUID.class);
            BigDecimal price=rs.getBigDecimal("public_unit_price_kzt");
            variants.computeIfAbsent(listingId,k->new ArrayList<>())
                .add(new PublicCatalogReadService.Variant(
                    rs.getObject("id",UUID.class),rs.getString("seller_sku_code"),
                    rs.getString("sales_unit"),
                    price==null?null:price.toPlainString()));
        });

        var items=selected.stream().map(row->{
            var item=row.item();
            return new PublicCatalogReadService.Item(item.listingId(),item.productId(),
                item.sellerId(),item.qrToken(),item.title(),item.category(),
                item.categoryCode(),item.productType(),
                List.copyOf(variants.getOrDefault(item.listingId(),List.of())));
        }).toList();

        SearchRow lastRow=selected.get(selected.size()-1);
        String next=hasMore?cursors.encode(term,category,kind,revision,
            new SearchCursorCodec.Position(lastRow.bucket(),lastRow.sortTitle(),
                lastRow.item().listingId())):null;
        return new SearchPage(items,next,hasMore);
    }

    @Transactional(readOnly=true,timeout=3)
    public List<Suggestion> suggest(String raw,String locale) {
        String term=normalize(raw);
        if(term.codePointCount(0,term.length())<2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Search prefix too short");
        if(locale==null||!locale.matches("ru|kk|en|zh"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid search locale");
        return jdbc.query("""
            SELECT st.term,st.locale,c.hcc_code,tc.relation_type
            FROM catalog_search_term st
            JOIN catalog_term_category tc ON tc.term_id=st.id
            JOIN catalog_category c ON c.id=tc.category_id
            WHERE st.status='ACTIVE' AND st.locale=:locale AND c.active=true
              AND tc.relation_type<>'RELATED'
              AND (st.normalized_term LIKE :prefix
                   OR similarity(st.normalized_term,:term)>=0.6)
            ORDER BY CASE WHEN st.normalized_term LIKE :prefix THEN 0 ELSE 1 END,
                     st.normalized_term,c.hcc_code
            LIMIT 10
            """,new MapSqlParameterSource()
                .addValue("term",term)
                .addValue("prefix",term+"%")
                .addValue("locale",locale),
            (rs,i)->new Suggestion(rs.getString(1),rs.getString(2),
                                    rs.getString(3),rs.getString(4)));
    }

    private static String validateCategory(String code) {
        if(code==null||code.isBlank()) return "";
        if(!code.matches("[0-9]{20}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid category code");
        return code;
    }

    private static String validateType(String type) {
        if(type==null||type.isBlank()) return "";
        if(!type.matches("PHYSICAL|SERVICE|DIGITAL"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid product type");
        return type;
    }

    private static String normalize(String raw) {
        if(raw==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Search text required");
        String text=Normalizer.normalize(raw,Normalizer.Form.NFC)
            .trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
        if(text.isEmpty()||text.codePointCount(0,text.length())>80 ||
            !text.matches("[\\p{L}\\p{N} .-]+"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid search text");
        return text;
    }
}
