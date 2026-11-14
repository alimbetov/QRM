package kz.zakaz.security;

import jakarta.persistence.*;
import java.net.URI;
import java.util.*;

/** Trusted mapping of a verified issuer+subject to an internal buyer UUID. */
@Entity @Table(name="buyer_account_link",uniqueConstraints=
    @UniqueConstraint(name="uk_buyer_issuer_subject",columnNames={"issuer","subject"}))
public class BuyerAccountLink {
    @Id @Column(nullable=false) private UUID id;
    @Column(nullable=false,length=255,updatable=false) private String issuer;
    @Column(nullable=false,length=255,updatable=false) private String subject;
    @Column(name="buyer_id",nullable=false,updatable=false) private UUID buyerId;
    @Column(nullable=false) private boolean active;
    @Version private Long version;
    protected BuyerAccountLink(){}
    /** Provisioned only by trusted account service; never by client-supplied buyerId. */
    public static BuyerAccountLink provision(String issuer,String subject,UUID buyerId) {
        if(issuer==null || issuer.length()>255)throw new IllegalArgumentException("Issuer");
        URI parsed;
        try{parsed=URI.create(issuer);}catch(IllegalArgumentException e){throw new IllegalArgumentException("Issuer",e);}
        if(!"https".equalsIgnoreCase(parsed.getScheme())||parsed.getHost()==null)
            throw new IllegalArgumentException("Trusted HTTPS issuer required");
        if(subject==null||subject.isBlank()||subject.length()>255)
            throw new IllegalArgumentException("Subject required");
        Objects.requireNonNull(buyerId);
        var link=new BuyerAccountLink();link.id=UUID.randomUUID();link.issuer=issuer;
        link.subject=subject;link.buyerId=buyerId;link.active=true;return link;
    }
    public UUID getBuyerId(){return buyerId;}
    public boolean isActive(){return active;}
}
