package kz.zakaz.publicqr;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

/**
 * A guest callback request is contact intent only, never buyer identity or an order.
 * No phone plaintext is stored. A source digest is pseudonymous, not identity proof.
 */
@Entity @Table(name="qr_purchase_lead",uniqueConstraints=
    @UniqueConstraint(name="uk_qr_lead_request",columnNames={"public_link_id","idempotency_key"}))
public class PurchaseLead {
    @Id @Column(nullable=false) private UUID id;
    @Version private Long version;
    @Column(name="public_link_id",nullable=false,updatable=false) private UUID publicLinkId;
    @Column(name="listing_id",nullable=false,updatable=false) private UUID listingId;
    @Column(name="idempotency_key",nullable=false,length=80,updatable=false) private String idempotencyKey;
    @Column(name="request_fingerprint",nullable=false,length=64,updatable=false)
    private String requestFingerprint;
    @Column(name="phone_ciphertext",nullable=false,length=256,updatable=false)
    private String phoneCiphertext;
    @Column(name="source_digest",nullable=false,length=64,updatable=false) private String sourceDigest;
    @Column(name="consent_at",nullable=false,updatable=false) private Instant consentAt;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    protected PurchaseLead(){}
    public static PurchaseLead record(UUID linkId,UUID listingId,String key,String fingerprint,
                                      String ciphertext,String sourceDigest,Instant now){
        Objects.requireNonNull(linkId);Objects.requireNonNull(listingId);Objects.requireNonNull(now);
        if(key==null||key.isBlank()||fingerprint==null||fingerprint.length()!=64
            ||ciphertext==null||ciphertext.isBlank()||sourceDigest==null||sourceDigest.length()!=64)
            throw new IllegalArgumentException("Incomplete lead");
        var lead=new PurchaseLead();lead.id=UUID.randomUUID();lead.publicLinkId=linkId;
        lead.listingId=listingId;lead.idempotencyKey=key;lead.requestFingerprint=fingerprint;
        lead.phoneCiphertext=ciphertext;lead.sourceDigest=sourceDigest;
        lead.consentAt=now;lead.createdAt=now;return lead;
    }
    public String getPhoneCiphertext(){return phoneCiphertext;}
    public String getRequestFingerprint(){return requestFingerprint;}
}
