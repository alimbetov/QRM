package kz.zakaz.publicqr;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseLeadRepository extends JpaRepository<PurchaseLead,UUID> {
    List<PurchaseLead> findByListingId(UUID listingId);
    Optional<PurchaseLead> findByPublicLinkIdAndIdempotencyKey(UUID publicLinkId,String key);
}
