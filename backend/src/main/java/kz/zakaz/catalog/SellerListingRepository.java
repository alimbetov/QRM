package kz.zakaz.catalog;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SellerListingRepository extends JpaRepository<SellerListing,UUID> {
    List<SellerListing> findBySellerId(UUID sellerId);
}
