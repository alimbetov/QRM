package kz.zakaz.media;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
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
}
