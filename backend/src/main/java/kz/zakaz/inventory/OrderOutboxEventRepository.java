package kz.zakaz.inventory;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OrderOutboxEventRepository extends JpaRepository<OrderOutboxEvent,UUID> {
    Optional<OrderOutboxEvent> findByAggregateIdAndEventType(UUID aggregateId,String eventType);
}
