package kz.zakaz.catalog;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CategoryRepository extends JpaRepository<Category,UUID> {
    Optional<Category> findByHccCode(String hccCode);
}
