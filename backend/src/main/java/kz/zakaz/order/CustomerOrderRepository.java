package kz.zakaz.order;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface CustomerOrderRepository extends JpaRepository<CustomerOrder,UUID> {
    Optional<CustomerOrder> findByPublicNumber(String publicNumber);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CustomerOrder o where o.id=:id")
    Optional<CustomerOrder> findForUpdate(@Param("id") UUID id);
}
