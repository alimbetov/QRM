package kz.zakaz.catalog;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

/** Feed is a thin projection on the existing live-authorized search read path. */
@Service
@ConditionalOnProperty(name="zakaz.catalog.feed.enabled",havingValue="true")
public final class PublicCatalogFeedService {
    public record FeedItem(UUID listingId,UUID productId,String title,String category,
        String categoryCode,String productType,String qrToken,
        List<PublicCatalogReadService.Variant> variants,String coverUrl,int imageCount) {}
    public record FeedPage(List<FeedItem> items,String nextCursor,boolean hasMore) {}
    private record Cover(UUID id,int imageCount){}
    private final PublicSearchReadService search;
    private final NamedParameterJdbcTemplate jdbc;
    private final boolean mediaEnabled;

    public PublicCatalogFeedService(PublicSearchReadService search,
        NamedParameterJdbcTemplate jdbc,
        @Value("${zakaz.catalog.media.enabled:false}") boolean mediaEnabled) {
        this.search=search;this.jdbc=jdbc;this.mediaEnabled=mediaEnabled;
    }

    public FeedPage feed(String query,String categoryCode,String productType,
                         int limit,String cursor) {
        var page=(query==null||query.isBlank())
            ?search.browse(categoryCode,productType,limit,cursor)
            :search.search(query,categoryCode,productType,limit,cursor);
        var covers=new HashMap<UUID,Cover>();
        if(mediaEnabled && !page.items().isEmpty()) {
            var ids=page.items().stream().map(PublicCatalogReadService.Item::listingId).toList();
            jdbc.query("""
                WITH choices AS (
                    SELECT m.listing_id,m.id,m.is_cover,
                           row_number() OVER (
                             PARTITION BY m.listing_id
                             ORDER BY m.is_cover DESC,m.sort_position,m.id) rn,
                           count(*) OVER (PARTITION BY m.listing_id) total
                    FROM catalog_listing_media m
                    JOIN catalog_listing l ON l.id=m.listing_id
                    JOIN catalog_product p ON p.id=l.product_id
                    JOIN catalog_category c ON c.id=p.category_id
                    JOIN qr_public_link q ON q.listing_id=l.id
                    WHERE m.listing_id IN (:ids) AND m.status='READY'
                      AND l.status='PUBLIC' AND c.active=true
                )
                SELECT listing_id,id,total FROM choices WHERE rn=1
                """,new MapSqlParameterSource("ids",ids),rs -> {
                covers.put(rs.getObject("listing_id",UUID.class),
                    new Cover(rs.getObject("id",UUID.class),rs.getInt("total")));
            });
        }
        var items=page.items().stream().map(item->{
            var cover=covers.get(item.listingId());
            return new FeedItem(item.listingId(),item.productId(),item.title(),
                item.category(),item.categoryCode(),item.productType(),
                item.qrToken(),item.variants(),
                cover==null?null:"/api/v1/public/media/"+cover.id()+"?size=thumb",
                cover==null?0:cover.imageCount());
        }).toList();
        return new FeedPage(items,page.nextCursor(),page.hasMore());
    }
}
