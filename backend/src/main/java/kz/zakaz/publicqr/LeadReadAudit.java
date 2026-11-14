package kz.zakaz.publicqr;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

/** Append-only evidence whenever a seller reads an encrypted guest contact. */
@Entity @Table(name="qr_lead_read_audit")
public class LeadReadAudit {
    @Id @Column(nullable=false) private UUID id;
    @Version private Long version;
    @Column(name="lead_id",nullable=false,updatable=false) private UUID leadId;
    @Column(name="seller_id",nullable=false,updatable=false) private UUID sellerId;
    @Column(name="actor_issuer",nullable=false,length=255,updatable=false) private String actorIssuer;
    @Column(name="actor_subject",nullable=false,length=255,updatable=false) private String actorSubject;
    @Column(name="read_at",nullable=false,updatable=false) private Instant readAt;
    protected LeadReadAudit(){}
    public static LeadReadAudit viewed(UUID leadId,UUID sellerId,
                       String issuer,String subject,Instant now) {
        Objects.requireNonNull(leadId);Objects.requireNonNull(sellerId);
        Objects.requireNonNull(issuer);Objects.requireNonNull(subject);Objects.requireNonNull(now);
        var audit=new LeadReadAudit();audit.id=UUID.randomUUID();audit.leadId=leadId;
        audit.sellerId=sellerId;audit.actorIssuer=issuer;audit.actorSubject=subject;
        audit.readAt=now;return audit;
    }
}
