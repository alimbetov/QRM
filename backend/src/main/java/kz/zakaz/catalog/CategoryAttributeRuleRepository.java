package kz.zakaz.catalog;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CategoryAttributeRuleRepository extends JpaRepository<CategoryAttributeRule,UUID> {
    List<CategoryAttributeRule> findByCategoryId(UUID categoryId);
}
