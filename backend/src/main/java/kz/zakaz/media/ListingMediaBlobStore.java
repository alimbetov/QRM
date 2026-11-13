package kz.zakaz.media;

/** Media bytes are private objects, never a public object-store URL. */
public interface ListingMediaBlobStore {
    void put(String key, byte[] data);
    byte[] get(String key);
    void delete(String key);
}
