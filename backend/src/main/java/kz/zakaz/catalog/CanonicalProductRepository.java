package kz.zakaz.catalog;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CanonicalProductRepository extends JpaRepository<CanonicalProduct,UUID> {}
