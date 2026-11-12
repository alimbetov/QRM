package kz.zakaz.catalog;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SkuAttributeValueRepository extends JpaRepository<SkuAttributeValue,UUID> {
    List<SkuAttributeValue> findBySkuId(UUID skuId);
}
