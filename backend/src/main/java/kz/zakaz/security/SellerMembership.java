package kz.zakaz.security;

import jakarta.persistence.*;
import java.net.URI;
import java.util.*;

/** Membership is server-provisioned: JWT seller_id is not trusted as ownership. */
@Entity
@Table(name="seller_membership",uniqueConstraints=@UniqueConstraint(
    name="uk_membership_issuer_subject_seller",columnNames={"issuer","subject","seller_id"}))
public class SellerMembership {
    public enum Role { CATALOG_EDITOR, SELLER_ADMIN, VIEWER }
    @Id @Column(nullable=false) private UUID id;
    @Column(nullable=false,length=255) private String issuer;
    @Column(nullable=false,length=255) private String subject;
    @Column(name="seller_id",nullable=false) private UUID sellerId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=24) private Role role;
    @Column(nullable=false) private boolean active;
    @Version private Long version;
    protected SellerMembership(){}
    /** Intended for audited administration provisioning only, never public HTTP. */
    public static SellerMembership grant(String issuer,String subject,UUID sellerId,Role role) {
        if(issuer==null || issuer.isBlank() || issuer.length()>255)
            throw new IllegalArgumentException("Issuer required");
        URI uri;
        try{ uri=URI.create(issuer); } catch(IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid issuer",e);
        }
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null)
            throw new IllegalArgumentException("Trusted issuer must be https URL");
        if(subject==null || subject.isBlank() || subject.length()>255)
            throw new IllegalArgumentException("Subject required");
        Objects.requireNonNull(sellerId);Objects.requireNonNull(role);
        var m=new SellerMembership();m.id=UUID.randomUUID();m.issuer=issuer;
        m.subject=subject;m.sellerId=sellerId;m.role=role;m.active=true;return m;
    }
    public boolean canEditCatalog() {
        return active&&(role==Role.CATALOG_EDITOR||role==Role.SELLER_ADMIN);
    }
    public boolean canConfirmOrders(){return active && role==Role.SELLER_ADMIN;}
    public Role getRole(){return role;}
    public UUID getSellerId(){return sellerId;}
    public String getIssuer(){return issuer;}
    public String getSubject(){return subject;}
}
