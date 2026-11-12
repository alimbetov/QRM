package kz.zakaz.security;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface BuyerAccountLinkRepository extends JpaRepository<BuyerAccountLink,UUID> {
    Optional<BuyerAccountLink> findByIssuerAndSubjectAndActiveTrue(String issuer,String subject);
}
