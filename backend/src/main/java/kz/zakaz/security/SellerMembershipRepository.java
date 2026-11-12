package kz.zakaz.security;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SellerMembershipRepository extends JpaRepository<SellerMembership,UUID> {
    Optional<SellerMembership> findByIssuerAndSubjectAndSellerIdAndActiveTrue(
        String issuer,String subject,UUID sellerId);
}
