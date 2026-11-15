package kz.zakaz.media;

import java.io.IOException;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Development/test adapter only. Not safe for production multi-node or CDN.
 * A production S3-compatible adapter must separately pass storage/ACL review.
 */
@Component
@Profile({"dev","test","test-pg"})
@ConditionalOnProperty(name="zakaz.media.storage.provider",havingValue="filesystem")
public final class LocalDevMediaBlobStore implements ListingMediaBlobStore {
    private final Path root;

    public LocalDevMediaBlobStore(@Value("${zakaz.media.storage.root:}") String directory) {
        if(directory==null||directory.isBlank())
            throw new IllegalStateException("Explicit media storage root is required");
        root=Path.of(directory).toAbsolutePath().normalize();
        try { Files.createDirectories(root); }
        catch(IOException ex) { throw new IllegalStateException("Cannot initialize media storage",ex); }
    }

    private Path path(String key) {
        if(key==null || !key.matches("listing/[0-9a-f-]{36}/[0-9a-f-]{36}/(full|thumb)\\.jpg"))
            throw new IllegalArgumentException("Invalid internal media key");
        Path resolved=root.resolve(key).normalize();
        if(!resolved.startsWith(root)) throw new SecurityException("Media path outside storage");
        return resolved;
    }

    @Override public void put(String key,byte[] data) {
        Path target=path(key), temp=null;
        if(data==null||data.length==0||data.length>6_000_000)
            throw new IllegalArgumentException("Invalid processed media length");
        try {
            Files.createDirectories(target.getParent());
            temp=Files.createTempFile(target.getParent(),".media-", ".tmp");
            Files.write(temp,data,StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException ex) { Files.move(temp,target); }
        } catch(IOException ex) { throw new IllegalStateException("Media write failed",ex); }
        finally {
            if(temp!=null) try { Files.deleteIfExists(temp); } catch(IOException ignored) {}
        }
    }

    @Override public byte[] get(String key) {
        try { return Files.readAllBytes(path(key)); }
        catch(IOException ex) { throw new IllegalStateException("Media read failed",ex); }
    }
    @Override public void delete(String key) {
        try { Files.deleteIfExists(path(key)); }
        catch(IOException ex) { throw new IllegalStateException("Media cleanup failed",ex); }
    }
}
