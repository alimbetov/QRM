package kz.zakaz.publicqr;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import kz.zakaz.security.SellerIdentityService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Seller can read own callback phones for contact purpose only.
 * Each disclosure is written to an immutable audit before the response commits.
 */
@Service
public class SellerLeadInboxService {
    public record LeadView(UUID leadId,UUID listingId,String phone,Instant receivedAt,String status){}
    private final SellerIdentityService identities;
    private final PurchaseLeadRepository leads;
    private final LeadPhoneCipher cipher;
    private final LeadReadAuditRepository audit;
    private final JdbcTemplate jdbc;
    public SellerLeadInboxService(SellerIdentityService identities,PurchaseLeadRepository leads,
                                  LeadPhoneCipher cipher,LeadReadAuditRepository audit,JdbcTemplate jdbc){
        this.identities=identities;this.leads=leads;this.cipher=cipher;this.audit=audit;
        this.jdbc=jdbc;
    }
    @Transactional
    public List<LeadView> read(JwtAuthenticationToken actor,UUID sellerId,int page,int size){
        var verifiedSeller=identities.requireSellerAdmin(actor,sellerId);
        if(page<0||page>1000||size<1||size>20)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid pagination");
        if(!cipher.configured())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Lead key unavailable");
        var records=leads.findRecentForSeller(verifiedSeller,PageRequest.of(page,size));
        var issuer=actor.getToken().getIssuer().toString();
        var subject=actor.getToken().getSubject();
        var now=Instant.now();
        var statuses=new HashMap<UUID,String>();
        if(!records.isEmpty()){
            String bind=String.join(",",Collections.nCopies(records.size(),"?"));
            var found=jdbc.query("SELECT lead_id,status FROM qr_lead_workflow WHERE lead_id IN ("+bind+")",
                (rs,i)->new Object[]{rs.getObject(1,UUID.class),rs.getString(2)},
                records.stream().map(PurchaseLead::getId).toArray());
            for(var row:found)statuses.put((UUID)row[0],(String)row[1]);
        }
        var response=new ArrayList<LeadView>();
        var entries=new ArrayList<LeadReadAudit>();
        for(var lead:records){
            response.add(new LeadView(lead.getId(),lead.getListingId(),
                cipher.decrypt(lead.getPhoneCiphertext()),lead.getCreatedAt(),
                statuses.getOrDefault(lead.getId(),"NEW")));
            entries.add(LeadReadAudit.viewed(lead.getId(),verifiedSeller,issuer,subject,now));
        }
        audit.saveAllAndFlush(entries);
        return List.copyOf(response);
    }
}
