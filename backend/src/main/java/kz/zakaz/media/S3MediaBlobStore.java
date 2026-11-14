package kz.zakaz.media;

import java.io.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Private RustFS/S3-compatible object storage; never returns raw bucket URLs. */
@Component
@ConditionalOnProperty(name="zakaz.media.storage.provider",havingValue="s3")
public final class S3MediaBlobStore implements ListingMediaBlobStore, AutoCloseable {
    private static final Pattern KEY=Pattern.compile(
        "listing/[0-9a-f-]{36}/[0-9a-f-]{36}/(full|thumb)\\.jpg");
    private static final int MAX_BYTES=6_000_000;
    private final S3Client client;
    private final String bucket;

    public S3MediaBlobStore(@Value("${zakaz.media.s3.endpoint:}") String endpoint,
        @Value("${zakaz.media.s3.bucket:}") String bucket,
        @Value("${zakaz.media.s3.region:us-east-1}") String region,
        @Value("${zakaz.media.s3.allow-local-http:false}") boolean allowLocalHttp) {
        if(bucket==null||!bucket.matches("[a-z0-9][a-z0-9.-]{2,62}"))
            throw new IllegalStateException("A private S3 bucket is required");
        if(endpoint==null||endpoint.isBlank())
            throw new IllegalStateException("Explicit S3 endpoint required");
        URI uri=URI.create(endpoint);
        String host=uri.getHost();
        boolean local=host!=null && (host.equals("localhost")||host.equals("127.0.0.1"));
        if(host==null||uri.getUserInfo()!=null||uri.getFragment()!=null||
            (!"https".equalsIgnoreCase(uri.getScheme())&&
             !(allowLocalHttp&&local&&"http".equalsIgnoreCase(uri.getScheme()))))
            throw new IllegalStateException("S3 endpoint must use HTTPS");
        this.bucket=bucket;
        client=S3Client.builder().region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .endpointOverride(uri).forcePathStyle(true).build();
    }

    S3MediaBlobStore(S3Client client,String bucket) {
        this.client=client;this.bucket=bucket;
    }

    private static String requireKey(String key) {
        if(key==null||!KEY.matcher(key).matches())
            throw new IllegalArgumentException("Invalid media object key");
        return key;
    }

    @Override public void put(String key,byte[] data) {
        key=requireKey(key);
        if(data==null||data.length<1||data.length>MAX_BYTES)
            throw new IllegalArgumentException("Invalid media size");
        client.putObject(PutObjectRequest.builder()
            .bucket(bucket).key(key).contentType("image/jpeg").build(),
            RequestBody.fromBytes(data));
    }

    @Override public byte[] get(String key) {
        try(var stream=client.getObject(GetObjectRequest.builder()
                    .bucket(bucket).key(requireKey(key)).build())) {
            if(stream.response().contentLength()!=null &&
               stream.response().contentLength()>MAX_BYTES)
                throw new IllegalStateException("Object exceeds media size limit");
            var out=new ByteArrayOutputStream();
            byte[] chunk=new byte[8192];int n,count=0;
            while((n=stream.read(chunk))!=-1) {
                count+=n;
                if(count>MAX_BYTES) throw new IllegalStateException("Object exceeds media limit");
                out.write(chunk,0,n);
            }
            return out.toByteArray();
        } catch(IOException ex) {
            throw new IllegalStateException("Private media read failed",ex);
        }
    }
    @Override public void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder()
            .bucket(bucket).key(requireKey(key)).build());
    }

    @Override public List<StoredObject> olderThan(Instant cutoff,int limit) {
        if(limit<1||limit>500) throw new IllegalArgumentException("Invalid orphan limit");
        var items=new ArrayList<StoredObject>();
        for(var page:client.listObjectsV2Paginator(ListObjectsV2Request.builder()
                .bucket(bucket).prefix("listing/").maxKeys(500).build())) {
            for(var object:page.contents()) {
                if(object.lastModified()!=null&&object.lastModified().isBefore(cutoff)
                    && KEY.matcher(object.key()).matches()) {
                    items.add(new StoredObject(object.key(),object.lastModified()));
                    if(items.size()==limit) return List.copyOf(items);
                }
            }
        }
        return List.copyOf(items);
    }
    @Override public void close() {client.close();}
}
