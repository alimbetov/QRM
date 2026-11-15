package kz.zakaz.catalog;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AttributeEnumOptionRepository extends JpaRepository<AttributeEnumOption,UUID> {
    boolean existsByAttributeIdAndCodeAndActiveTrue(UUID attributeId,String code);
}
