package kz.zakaz.inventory;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement,UUID> {
    Optional<InventoryMovement> findBySellerIdAndKindAndSourceKey(
        UUID sellerId,InventoryMovement.Kind kind,String sourceKey);
    List<InventoryMovement> findByOrderId(UUID orderId);
}
