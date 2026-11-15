package kz.zakaz.deal;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerBidSnapshotRepository extends JpaRepository<SellerBidSnapshot,UUID> {
    Optional<SellerBidSnapshot> findFirstByDemand_IdAndSellerIdOrderByRevisionDesc(
        UUID demandId,UUID sellerId);
}
