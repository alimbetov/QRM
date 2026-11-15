package kz.zakaz.publicqr;

import jakarta.persistence.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import kz.zakaz.catalog.SellerListing;

/** Random opaque QR identity. Listing UUID/seller ID/phone must not be encoded in token. */
@Entity @Table(name="qr_public_link",uniqueConstraints={
    @UniqueConstraint(name="uk_qr_link_listing",columnNames="listing_id"),
    @UniqueConstraint(name="uk_qr_link_token",columnNames="token")
})
public class PublicListingLink {
    private static final SecureRandom RANDOM=new SecureRandom();
    @Id @Column(nullable=false) private UUID id;
    @Version private Long version;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="listing_id",nullable=false,updatable=false)
    private SellerListing listing;
    @Column(nullable=false,length=64,updatable=false) private String token;
    @Column(name="issued_at",nullable=false,updatable=false) private Instant issuedAt;
    protected PublicListingLink(){}
    public static PublicListingLink issue(SellerListing listing) {
        Objects.requireNonNull(listing);
        byte[] entropy=new byte[32];RANDOM.nextBytes(entropy);
        var link=new PublicListingLink();link.id=UUID.randomUUID();
        link.listing=listing;link.token=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        link.issuedAt=Instant.now();return link;
    }
    public UUID getId(){return id;}
    public String getToken(){return token;}
    public SellerListing getListing(){return listing;}
}
