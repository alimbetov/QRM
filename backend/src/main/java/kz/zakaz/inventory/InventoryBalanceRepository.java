package kz.zakaz.inventory;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface InventoryBalanceRepository extends JpaRepository<InventoryBalance,UUID> {
    Optional<InventoryBalance> findBySellerIdAndLocationIdAndSkuId(
        UUID sellerId,UUID locationId,UUID skuId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from InventoryBalance b where b.sellerId=:sellerId and "+
           "b.locationId=:locationId and b.skuId=:skuId")
    Optional<InventoryBalance> findForUpdate(@Param("sellerId") UUID sellerId,
                            @Param("locationId") UUID locationId,@Param("skuId") UUID skuId);
}
