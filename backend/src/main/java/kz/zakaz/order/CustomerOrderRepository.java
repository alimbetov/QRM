package kz.zakaz.order;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CustomerOrderRepository extends JpaRepository<CustomerOrder,UUID>{
 Optional<CustomerOrder> findByPublicNumber(String publicNumber);
}