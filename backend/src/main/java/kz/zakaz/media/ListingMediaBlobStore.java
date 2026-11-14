package kz.zakaz.media;

/** Media bytes are private objects, never a public object-store URL. */
public interface ListingMediaBlobStore {
    record StoredObject(String key,java.time.Instant modifiedAt) {}
    default java.util.List<StoredObject> olderThan(java.time.Instant cutoff,int limit) {
        throw new UnsupportedOperationException("Object listing is not supported");
    }
    void put(String key, byte[] data);
    byte[] get(String key);
    void delete(String key);
}
