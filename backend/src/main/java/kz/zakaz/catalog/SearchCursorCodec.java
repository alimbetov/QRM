package kz.zakaz.catalog;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * HMAC-signed, expiring keyset cursor. A cursor is only usable for the exact
 * normalized query/filter and dictionary revision it was issued for.
 * A search cursor is not an authorization token.
 */
@Component
@ConditionalOnProperty(name="zakaz.search.http.enabled",havingValue="true")
final class SearchCursorCodec {
    record Position(int bucket,String sortTitle,UUID listingId) {}

    private static final int FORMAT_VERSION=1;
    private static final long TTL_SECONDS=15*60L;
    private final byte[] secret;

    SearchCursorCodec(@Value("${zakaz.search.cursor-secret-base64:}") String secretBase64) {
        try {
            this.secret=Base64.getDecoder().decode(secretBase64);
        } catch(IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid SEARCH cursor secret encoding",ex);
        }
        if(secret.length<32) throw new IllegalStateException(
            "SEARCH requires a private random cursor-secret-base64 of at least 32 bytes");
    }

    String encode(String query,String categoryCode,String productType,long revision,Position last) {
        try {
            var data=new ByteArrayOutputStream();
            try(var out=new DataOutputStream(data)) {
                out.writeInt(FORMAT_VERSION);
                out.writeUTF(query);
                out.writeUTF(categoryCode==null?"":categoryCode);
                out.writeUTF(productType==null?"":productType);
                out.writeLong(revision);
                out.writeInt(last.bucket());
                out.writeUTF(last.sortTitle());
                out.writeLong(last.listingId().getMostSignificantBits());
                out.writeLong(last.listingId().getLeastSignificantBits());
                out.writeLong(Instant.now().plusSeconds(TTL_SECONDS).getEpochSecond());
            }
            byte[] payload=data.toByteArray();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)+"."+
                Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload));
        } catch(IOException impossible) {
            throw new IllegalStateException("Could not encode cursor",impossible);
        }
    }

    Position decode(String cursor,String query,String categoryCode,String productType,long revision) {
        if(cursor==null||cursor.isBlank()) return null;
        if(cursor.length()>2048) throw badCursor();
        try {
            String[] parts=cursor.split("\\.",-1);
            if(parts.length!=2) throw badCursor();
            byte[] payload=Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature=Base64.getUrlDecoder().decode(parts[1]);
            if(!MessageDigest.isEqual(mac(payload),signature)) throw badCursor();
            try(var in=new DataInputStream(new ByteArrayInputStream(payload))) {
                if(in.readInt()!=FORMAT_VERSION) throw badCursor();
                String issuedQuery=in.readUTF();
                String issuedCategory=in.readUTF();
                String issuedType=in.readUTF();
                long issuedRevision=in.readLong();
                int rank=in.readInt();
                String sortTitle=in.readUTF();
                UUID last=new UUID(in.readLong(),in.readLong());
                long expiry=in.readLong();
                if(in.available()!=0 || !query.equals(issuedQuery) ||
                    !(categoryCode==null?"":categoryCode).equals(issuedCategory) ||
                    !(productType==null?"":productType).equals(issuedType) ||
                    rank<0 || rank>4 || sortTitle.length()>200)
                    throw badCursor();
                if(issuedRevision!=revision)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Search dictionary changed; restart search");
                if(Instant.now().getEpochSecond()>expiry) throw badCursor();
                return new Position(rank,sortTitle,last);
            }
        } catch(IllegalArgumentException | IOException ex) {
            throw badCursor();
        }
    }

    private byte[] mac(byte[] payload) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            return mac.doFinal(payload);
        } catch(Exception ex) {
            throw new IllegalStateException("Cursor HMAC unavailable",ex);
        }
    }

    private static ResponseStatusException badCursor() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid search cursor");
    }
}
