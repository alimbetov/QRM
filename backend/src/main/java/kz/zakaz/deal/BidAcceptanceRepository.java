package kz.zakaz.deal;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface BidAcceptanceRepository extends JpaRepository<BidAcceptance,UUID> {
    Optional<BidAcceptance> findByBuyerIdAndIdempotencyKey(UUID buyerId,String idempotencyKey);
    boolean existsByDemandId(UUID demandId);
}
