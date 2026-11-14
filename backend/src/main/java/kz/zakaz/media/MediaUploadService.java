package kz.zakaz.media;

import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
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
    private final MediaMalwareScanner scanner;
    private final ListingMediaBlobStore store;
    private final Semaphore uploadPermits;

    public MediaUploadService(SellerListingRepository listings,JdbcTemplate jdbc,
            TransactionTemplate transactions,MediaImageSanitizer sanitizer,
            MediaMalwareScanner scanner,ListingMediaBlobStore store,
            @Value("${zakaz.media.upload.max-concurrent:2}") int maxConcurrentUploads) {
        this.listings=listings;this.jdbc=jdbc;this.transactions=transactions;
        this.sanitizer=sanitizer;this.scanner=scanner;this.store=store;
        if(maxConcurrentUploads<1||maxConcurrentUploads>8)
            throw new IllegalStateException("Media upload concurrency must be 1..8");
        this.uploadPermits=new Semaphore(maxConcurrentUploads,true);
    }

    public UUID upload(UUID sellerId,UUID listingId,MultipartFile image,boolean cover) {
        if(!uploadPermits.tryAcquire())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                                              "Too many concurrent media uploads");
        try {
            return uploadWithPermit(sellerId,listingId,image,cover);
        } finally {
            uploadPermits.release();
        }
    }

    private UUID uploadWithPermit(UUID sellerId,UUID listingId,MultipartFile image,boolean cover) {
        if(sellerId==null||listingId==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Seller/listing required");
        // Reject forged SellerListing BEFORE expensive scanning/object-store writes.
        var draft=listings.findById(listingId)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Listing not found"));
        if(!sellerId.equals(draft.getSellerId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Listing not found");
        if(draft.getStatus()!=ListingStatus.DRAFT)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Media changes require DRAFT");
        // Read the bounded multipart payload once, then reuse the same buffer
        // for malware scanning and image decoding. No direct client-to-S3 upload.
        byte[] original;
        try {
            if(image==null||image.isEmpty()||image.getSize()>5_000_000)
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid image");
            original=image.getBytes();
        } catch(java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Unreadable image");
        }
        if(original.length==0||original.length>5_000_000)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid image");
        scanner.requireClean(original);
        var ready=sanitizer.sanitize(original);
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
