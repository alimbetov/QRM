package kz.zakaz.media;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OFF by default. Cross-instance singleton sweep uses a PostgreSQL advisory
 * transaction lock, so it does NOT require Redis or MongoDB. The lock is
 * released by COMMIT/ROLLBACK even when the process fails.
 *
 * Deletes only objects older than 48h and absent from catalog_listing_media.
 * A bounded batch avoids holding the lease for unbounded S3 pagination.
 */
@Component
@ConditionalOnProperty(name="zakaz.media.gc.enabled",havingValue="true")
public final class MediaOrphanReconciler {
    private static final int LOCK_NAMESPACE=7124;
    private static final int LOCK_ID=23;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final ListingMediaBlobStore blobs;

    public MediaOrphanReconciler(DataSource dataSource,JdbcTemplate jdbc,
                                 ListingMediaBlobStore blobs) {
        this.dataSource=dataSource;
        this.jdbc=jdbc;
        this.blobs=blobs;
    }

    @Scheduled(fixedDelayString="${zakaz.media.gc.interval-ms:21600000}")
    public void sweep() {
        try(Connection lease=dataSource.getConnection()) {
            // Keep this connection checked out for the entire sweep. The lease
            // has no catalog writes; audit rows commit via JdbcTemplate.
            lease.setAutoCommit(false);
            try {
                if(!tryAcquireLease(lease)) {
                    lease.rollback();
                    return;
                }
                removeUnreferenced();
                lease.commit();
            } catch(Exception ex) {
                lease.rollback();
                if(ex instanceof RuntimeException re) throw re;
                throw new IllegalStateException("Media GC lease failed",ex);
            }
        } catch(SQLException ex) {
            throw new IllegalStateException("Media GC database unavailable",ex);
        }
    }

    private boolean tryAcquireLease(Connection connection) throws SQLException {
        try(PreparedStatement stmt=connection.prepareStatement(
                "SELECT pg_try_advisory_xact_lock(?,?)")) {
            stmt.setInt(1,LOCK_NAMESPACE);
            stmt.setInt(2,LOCK_ID);
            try(ResultSet rs=stmt.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private void removeUnreferenced() {
        Instant cutoff=Instant.now().minus(Duration.ofHours(48));
        // GC remains opt-in; keep this batch bounded to protect connections.
        for(var item:blobs.olderThan(cutoff,50)) {
            String key=item.key();
            if(key==null || (!key.endsWith("/thumb.jpg") &&
                              !key.endsWith("/full.jpg"))) continue;
            String full=key.replace("/thumb.jpg","/full.jpg");
            Integer linked=jdbc.queryForObject(
                "SELECT count(*) FROM catalog_listing_media WHERE object_key=?",
                Integer.class,full);
            if(linked==null || linked!=0) continue;
            blobs.delete(key);
            // A failed audit after deletion is surfaced rather than ignored.
            jdbc.update("""
                INSERT INTO catalog_media_orphan_gc_event(id,object_key,deleted_at)
                VALUES (?,?,clock_timestamp())
                """,UUID.randomUUID(),key);
        }
    }
}
