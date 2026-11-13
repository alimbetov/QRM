package kz.zakaz.media;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Public media is re-authorized against LIVE Listing/Category/QR on every read. */
@Service
@ConditionalOnProperty(name="zakaz.catalog.media.enabled",havingValue="true")
public class PublicMediaReadService {
    public record ImageView(UUID mediaId,boolean cover,int width,int height,
                            String thumbnailUrl,String imageUrl){}
    private record MediaRef(String key){}
    private final JdbcTemplate jdbc;
    private final ListingMediaBlobStore store;

    public PublicMediaReadService(JdbcTemplate jdbc,ListingMediaBlobStore store) {
        this.jdbc=jdbc;this.store=store;
    }
    private static final String VISIBLE = """
        FROM catalog_listing_media m
        JOIN catalog_listing l ON l.id=m.listing_id
        JOIN catalog_product p ON p.id=l.product_id
        JOIN catalog_category c ON c.id=p.category_id
        JOIN qr_public_link q ON q.listing_id=l.id
        WHERE m.status='READY' AND l.status='PUBLIC' AND c.active=true
        """;

    @Transactional(readOnly=true,timeout=3)
    public List<ImageView> gallery(UUID listingId) {
        // An empty gallery of a public Listing is legitimate for legacy listings.
        Integer available=jdbc.queryForObject("""
            SELECT count(*) FROM catalog_listing l
            JOIN catalog_product p ON p.id=l.product_id
            JOIN catalog_category c ON c.id=p.category_id
            JOIN qr_public_link q ON q.listing_id=l.id
            WHERE l.id=? AND l.status='PUBLIC' AND c.active=true
            """,Integer.class,listingId);
        if(available==null||available==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Listing unavailable");
        return jdbc.query("""
            SELECT m.id,m.is_cover,m.width,m.height
            """+VISIBLE+"""
            AND l.id=?
            ORDER BY m.sort_position,m.id LIMIT 20
            """,(rs,i)->{
                UUID id=rs.getObject("id",UUID.class);
                String path="/api/v1/public/media/"+id;
                return new ImageView(id,rs.getBoolean("is_cover"),
                    rs.getInt("width"),rs.getInt("height"),
                    path+"?size=thumb",path+"?size=full");
            },listingId);
    }

    @Transactional(readOnly=true,timeout=3)
    public byte[] image(UUID mediaId,boolean thumb) {
        List<MediaRef> refs=jdbc.query("""
            SELECT m.object_key
            """+VISIBLE+"""
            AND m.id=? LIMIT 1
            """,(rs,i)->new MediaRef(rs.getString("object_key")),mediaId);
        if(refs.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Image unavailable");
        String key=refs.get(0).key();
        if(thumb) key=key.substring(0,key.length()-"full.jpg".length())+"thumb.jpg";
        return store.get(key);
    }
}
