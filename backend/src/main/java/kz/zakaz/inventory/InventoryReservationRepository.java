package kz.zakaz.inventory;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface InventoryReservationRepository extends JpaRepository<InventoryReservation,UUID> {
    List<InventoryReservation> findByOrderId(UUID orderId);
}
