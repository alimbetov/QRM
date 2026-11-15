package kz.zakaz.catalog;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface SellerListingRepository extends JpaRepository<SellerListing,UUID> {
    List<SellerListing> findBySellerId(UUID sellerId);
    org.springframework.data.domain.Page<SellerListing> findBySellerId(
        UUID sellerId,org.springframework.data.domain.Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from SellerListing l where l.id = :id")
    Optional<SellerListing> findForUpdate(@Param("id") UUID id);
}
