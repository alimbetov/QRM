package kz.zakaz.publicqr;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

public interface PurchaseLeadRepository extends JpaRepository<PurchaseLead,UUID> {
    List<PurchaseLead> findByListingId(UUID listingId);
    @Query("select p from PurchaseLead p, SellerListing l where p.listingId=l.id " +
           "and l.sellerId=:sellerId order by p.createdAt desc, p.id desc")
    List<PurchaseLead> findRecentForSeller(@Param("sellerId") UUID sellerId, Pageable page);
    Optional<PurchaseLead> findByPublicLinkIdAndIdempotencyKey(UUID publicLinkId,String key);
}
