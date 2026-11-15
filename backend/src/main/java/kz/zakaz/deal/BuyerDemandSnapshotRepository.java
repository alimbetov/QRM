package kz.zakaz.deal;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface BuyerDemandSnapshotRepository extends JpaRepository<BuyerDemandSnapshot,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from BuyerDemandSnapshot d where d.id=:demandId")
    Optional<BuyerDemandSnapshot> findForUpdate(@Param("demandId") UUID demandId);
}
