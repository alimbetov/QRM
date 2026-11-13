package kz.zakaz.media;

import java.time.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OFF by default: remove ONLY unreferenced objects older than 48h.
 * Run one worker with an operational lease on multi-node deployments.
 */
@Component
@ConditionalOnProperty(name="zakaz.media.gc.enabled",havingValue="true")
public final class MediaOrphanReconciler {
    private final JdbcTemplate jdbc;
    private final ListingMediaBlobStore blobs;

    public MediaOrphanReconciler(JdbcTemplate jdbc,ListingMediaBlobStore blobs) {
        this.jdbc=jdbc;this.blobs=blobs;
    }

    @Scheduled(fixedDelayString="${zakaz.media.gc.interval-ms:21600000}")
    public void sweep() {
        for(var item:blobs.olderThan(Instant.now().minus(Duration.ofHours(48)),250)) {
            String original=item.key().replace("/thumb.jpg","/full.jpg");
            Integer linked=jdbc.queryForObject(
                "SELECT count(*) FROM catalog_listing_media WHERE object_key=?",
                Integer.class,original);
            if(linked!=null&&linked==0) blobs.delete(item.key());
        }
    }
}
