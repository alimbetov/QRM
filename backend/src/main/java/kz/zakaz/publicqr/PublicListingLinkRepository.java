package kz.zakaz.publicqr;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicListingLinkRepository extends JpaRepository<PublicListingLink,UUID> {
    Optional<PublicListingLink> findByToken(String token);
    Optional<PublicListingLink> findByListing_Id(UUID listingId);
}
