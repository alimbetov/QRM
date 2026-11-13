package kz.zakaz.publicqr;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Guest callback: no SMS assertion, no CustomerOrder, no client-controlled seller. */
@Service
public class GuestLeadService {
    private final PublicQrCatalogService catalog;
    private final PurchaseLeadRepository leads;
    private final LeadPhoneCipher cipher;
    private final JdbcTemplate jdbc;
    public GuestLeadService(PublicQrCatalogService catalog,PurchaseLeadRepository leads,
                            LeadPhoneCipher cipher,JdbcTemplate jdbc) {
        this.catalog=catalog;this.leads=leads;this.cipher=cipher;this.jdbc=jdbc;
    }
    @Transactional
    public void submit(String token,String key,String phone,boolean consent,String remoteAddress) {
        if(!consent||phone==null||!phone.matches("\\+[1-9][0-9]{7,14}")
                ||key==null||!key.matches("[A-Za-z0-9_.:-]{1,80}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid guest contact request");
        if(!cipher.configured())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Contact intake not configured");
        var link=catalog.findPublicLink(token);
        // Lock a key-specific advisory mutex to make simultaneous retries deterministic.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
            (org.springframework.jdbc.core.RowCallbackHandler) rs->{},
            link.getId()+"|"+key);
        var fingerprint=cipher.digest(link.getId()+"|"+phone);
        var previous=leads.findByPublicLinkIdAndIdempotencyKey(link.getId(),key);
        if(previous.isPresent()){
            if(!previous.get().getRequestFingerprint().equals(fingerprint))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key reused");
            return;
        }
        // Ignore untrusted X-Forwarded-For. A separately configured trusted proxy
        // should expose the real remote address to the application/container.
        var source=cipher.digest("remote:"+Objects.toString(remoteAddress,"unknown"));
        var attempts=jdbc.query("""
            INSERT INTO qr_lead_rate_bucket(listing_id,source_digest,window_bucket,attempts)
            VALUES(?,?,FLOOR(EXTRACT(EPOCH FROM clock_timestamp())/900)::bigint,1)
            ON CONFLICT(listing_id,source_digest,window_bucket)
            DO UPDATE SET attempts=qr_lead_rate_bucket.attempts+1
                WHERE qr_lead_rate_bucket.attempts<5
            RETURNING attempts
            """,(rs,i)->rs.getInt(1),link.getListing().getId(),source);
        if(attempts.isEmpty())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Too many contact requests");
        leads.saveAndFlush(PurchaseLead.record(
            link.getId(),link.getListing().getId(),key,fingerprint,cipher.encrypt(phone),
            source,Instant.now()));
    }
}
