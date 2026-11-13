package kz.zakaz.security;

import java.time.Instant;
import java.util.*;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Explicit opt-in buyer onboarding. The verified token provides issuer/subject/email;
 * client input can NEVER assign buyerId, sellerId, role or verified-phone status.
 */
@Service
public class BuyerEnrollmentService {
    private final JdbcTemplate jdbc;
    private final BuyerAccountLinkRepository accounts;
    private final BuyerPhoneCipher cipher;

    public record Profile(UUID buyerId,String nickname,String maskedPhone,
                          boolean phoneVerified,String email,boolean created) {}

    public BuyerEnrollmentService(JdbcTemplate jdbc,BuyerAccountLinkRepository accounts,
                                  BuyerPhoneCipher cipher) {
        this.jdbc=jdbc;
        this.accounts=accounts;
        this.cipher=cipher;
    }

    private record Identity(String issuer,String subject,String email) {}

    private Identity identity(JwtAuthenticationToken token) {
        if(token==null || !token.isAuthenticated() || token.getToken()==null ||
           token.getToken().getIssuer()==null || token.getToken().getSubject()==null)
            throw new AccessDeniedException("A verified OIDC identity is required");
        var jwt=token.getToken();
        String email=jwt.getClaimAsString("email");
        if (!Boolean.TRUE.equals(jwt.getClaim("email_verified"))
            || email==null || email.isBlank() || email.length()>254
            || !email.contains("@"))
            throw new AccessDeniedException("Verified email identity required");
        return new Identity(jwt.getIssuer().toString(),jwt.getSubject(),email);
    }

    private Profile existingProfile(UUID buyerId,String email) {
        var rows=jdbc.query("""
          SELECT nickname,phone_last_four,phone_verified
          FROM buyer_profile WHERE buyer_id=?
          """,(rs,i)->new Profile(buyerId,rs.getString("nickname"),
                 "••••"+rs.getString("phone_last_four"),
                 rs.getBoolean("phone_verified"),email,false),buyerId);
        return rows.isEmpty()?null:rows.getFirst();
    }

    @Transactional(readOnly=true)
    public Profile me(JwtAuthenticationToken token) {
        var identity=identity(token);
        var account=accounts.findByIssuerAndSubjectAndActiveTrue(identity.issuer(),identity.subject())
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Buyer enrollment required"));
        var profile=existingProfile(account.getBuyerId(),identity.email());
        if(profile==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Buyer profile incomplete");
        return profile;
    }

    @Transactional
    public Profile enroll(JwtAuthenticationToken token,String nickname,String phone,boolean consent) {
        var identity=identity(token);
        if(nickname==null || !nickname.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}_.-]{2,39}")
            || phone==null || !phone.matches("\\+[1-9][0-9]{7,14}") || !consent)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid enrollment");
        if(!cipher.configured())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Buyer encryption key not configured");

        // Serializes registration retries across JVM replicas. Unique (issuer,subject) in V7
        // is the permanent DB authority; the advisory lock is not a substitute for it.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
            (org.springframework.jdbc.core.RowCallbackHandler) rs->{},
            "zakaz-enroll|"+identity.issuer()+"|"+identity.subject());

        var link=accounts.findByIssuerAndSubject(identity.issuer(),identity.subject());
        if(link.isPresent() && !link.get().isActive())
            throw new AccessDeniedException("Buyer identity is deactivated");
        UUID buyerId;
        if(link.isPresent()) buyerId=link.get().getBuyerId();
        else {
            buyerId=UUID.randomUUID();
            accounts.saveAndFlush(BuyerAccountLink.provision(
                identity.issuer(),identity.subject(),buyerId));
        }
        var prior=existingProfile(buyerId,identity.email());
        if(prior!=null) return prior;

        String encrypted=cipher.encrypt(phone,identity.issuer(),identity.subject());
        Instant now=Instant.now();
        jdbc.update("""
            INSERT INTO buyer_profile
              (buyer_id,nickname,phone_ciphertext,phone_last_four,phone_verified,
               consent_to_store_phone_at,created_at)
            VALUES (?,?,?,?,false,?,?)
            """,buyerId,nickname,encrypted,phone.substring(phone.length()-4),
            java.sql.Timestamp.from(now),java.sql.Timestamp.from(now));
        return new Profile(buyerId,nickname,"••••"+phone.substring(phone.length()-4),
                           false,identity.email(),true);
    }
}
