package kz.zakaz.media;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;

/** PostgreSQL advisory transaction lock: only one media GC leader across nodes. */
class MediaOrphanReconcilerTest {
    @Test void anotherWorkerHoldingLeasePreventsStorageScan() throws Exception {
        var ds=mock(DataSource.class);
        var connection=mock(Connection.class);
        var stmt=mock(PreparedStatement.class);
        var rs=mock(ResultSet.class);
        var blobs=mock(ListingMediaBlobStore.class);
        var jdbc=mock(JdbcTemplate.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?,?)"))
            .thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean(1)).thenReturn(false);

        new MediaOrphanReconciler(ds,jdbc,blobs).sweep();

        verify(connection).rollback();
        verify(connection,never()).commit();
        verify(blobs,never()).olderThan(any(),anyInt());
    }

    @Test void acquiredLeaseIsCommittedEvenWithNoOldObjects() throws Exception {
        var ds=mock(DataSource.class);
        var connection=mock(Connection.class);
        var stmt=mock(PreparedStatement.class);
        var rs=mock(ResultSet.class);
        var blobs=mock(ListingMediaBlobStore.class);
        var jdbc=mock(JdbcTemplate.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?,?)"))
            .thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean(1)).thenReturn(true);
        when(blobs.olderThan(any(),eq(50))).thenReturn(List.of());

        new MediaOrphanReconciler(ds,jdbc,blobs).sweep();

        verify(connection).commit();
        verify(blobs).olderThan(any(),eq(50));
    }
    @Test void referencedObjectIsKeptEvenWhenOld() throws Exception {
        var ds=mock(DataSource.class);
        var connection=mock(Connection.class);
        var stmt=mock(PreparedStatement.class);
        var rs=mock(ResultSet.class);
        var blobs=mock(ListingMediaBlobStore.class);
        var jdbc=mock(JdbcTemplate.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?,?)")).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean(1)).thenReturn(true);
        String key="listing/"+UUID.randomUUID()+"/"+UUID.randomUUID()+"/thumb.jpg";
        when(blobs.olderThan(any(),eq(50))).thenReturn(List.of(
            new ListingMediaBlobStore.StoredObject(key,Instant.now().minusSeconds(200000))));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any())).thenReturn(1);

        new MediaOrphanReconciler(ds,jdbc,blobs).sweep();
        verify(blobs,never()).delete(anyString());
        verify(connection).commit();
    }

    @Test void unreferencedObjectIsDeletedAndAudited() throws Exception {
        var ds=mock(DataSource.class);
        var connection=mock(Connection.class);
        var stmt=mock(PreparedStatement.class);
        var rs=mock(ResultSet.class);
        var blobs=mock(ListingMediaBlobStore.class);
        var jdbc=mock(JdbcTemplate.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?,?)")).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean(1)).thenReturn(true);
        String key="listing/"+UUID.randomUUID()+"/"+UUID.randomUUID()+"/thumb.jpg";
        when(blobs.olderThan(any(),eq(50))).thenReturn(List.of(
            new ListingMediaBlobStore.StoredObject(key,Instant.now().minusSeconds(200000))));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any())).thenReturn(0);

        new MediaOrphanReconciler(ds,jdbc,blobs).sweep();
        verify(blobs).delete(key);
        verify(jdbc).update(anyString(),any(UUID.class),eq(key));
        verify(connection).commit();
    }

    @Test void failedObjectScanRollsBackLeaderLease() throws Exception {
        var ds=mock(DataSource.class);
        var connection=mock(Connection.class);
        var stmt=mock(PreparedStatement.class);
        var rs=mock(ResultSet.class);
        var blobs=mock(ListingMediaBlobStore.class);
        var jdbc=mock(JdbcTemplate.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?,?)")).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean(1)).thenReturn(true);
        when(blobs.olderThan(any(),eq(50))).thenThrow(new IllegalStateException("RustFS unreachable"));

        assertThatThrownBy(()->new MediaOrphanReconciler(ds,jdbc,blobs).sweep())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("RustFS");
        verify(connection).rollback();
        verify(connection,never()).commit();
    }

}
