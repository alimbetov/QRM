package kz.zakaz.media;

import java.util.UUID;
import kz.zakaz.catalog.SellerListingRepository;
import kz.zakaz.domain.types.ListingStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * IMAGE upload: sanitized private objects first; metadata committed only under
 * the same SellerListing pessimistic lock as publish. On DB failure remove staged
 * objects. Crash-orphan reconciliation / multi-node S3 are future production gates.
 */
@Service
@ConditionalOnProperty(name="zakaz.catalog.media.enabled",havingValue="true")
public class MediaUploadService {
    private final SellerListingRepository listings;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final MediaImageSanitizer sanitizer;
    private final ListingMediaBlobStore store;

    public MediaUploadService(SellerListingRepository listings,JdbcTemplate jdbc,
            TransactionTemplate transactions,MediaImageSanitizer sanitizer,ListingMediaBlobStore store) {
        this.listings=listings;this.jdbc=jdbc;this.transactions=transactions;
        this.sanitizer=sanitizer;this.store=store;
    }

    public UUID upload(UUID sellerId,UUID listingId,MultipartFile image,boolean cover) {
        if(sellerId==null||listingId==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Seller/listing required");
        var ready=sanitizer.sanitize(image);
        UUID mediaId=UUID.randomUUID();
        String prefix="listing/"+listingId+"/"+mediaId+"/";
        String fullKey=prefix+"full.jpg",thumbKey=prefix+"thumb.jpg";
        try {
            store.put(fullKey,ready.full());
            store.put(thumbKey,ready.thumbnail());
            return transactions.execute(tx->{
                var listing=listings.findForUpdate(listingId)
                    .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Listing not found"));
                if(!sellerId.equals(listing.getSellerId()))
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Listing not found");
                if(listing.getStatus()!=ListingStatus.DRAFT)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Media changes require DRAFT");
                Integer count=jdbc.queryForObject("""
                    SELECT count(*) FROM catalog_listing_media
                    WHERE listing_id=? AND status<>'REVOKED'
                    """,Integer.class,listingId);
                if(count==null||count>=20)
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                                     "Media limit reached");
                try {
                    jdbc.update("""
                        INSERT INTO catalog_listing_media (
                            id,listing_id,object_key,content_type,sha256,width,height,
                            sort_position,is_cover,status,created_at)
                        VALUES(?,?,?,'image/jpeg',?,?,?,?,?,'READY',clock_timestamp())
                        """,mediaId,listingId,fullKey,ready.sha256(),ready.width(),ready.height(),
                            count,cover||count==0);
                } catch(DataIntegrityViolationException ex) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                                                      "Conflicting media cover or metadata");
                }
                return mediaId;
            });
        } catch(RuntimeException ex) {
            try {store.delete(thumbKey);} catch(RuntimeException ignored) {}
            try {store.delete(fullKey);} catch(RuntimeException ignored) {}
            throw ex;
        }
    }
}
