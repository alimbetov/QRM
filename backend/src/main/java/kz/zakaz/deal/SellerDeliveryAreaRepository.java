package kz.zakaz.deal;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SellerDeliveryAreaRepository extends JpaRepository<SellerDeliveryArea,UUID> {
    boolean existsBySellerIdAndCityAndActiveTrue(UUID sellerId,String city);
}
