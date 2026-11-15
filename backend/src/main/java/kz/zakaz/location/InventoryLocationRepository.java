package kz.zakaz.location;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface InventoryLocationRepository extends JpaRepository<InventoryLocation,UUID>{}
