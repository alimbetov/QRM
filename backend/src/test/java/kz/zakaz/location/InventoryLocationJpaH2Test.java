package kz.zakaz.location;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.UUID;
import kz.zakaz.domain.types.LocationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
@DataJpaTest @ActiveProfiles("test")
class InventoryLocationJpaH2Test {
 @Autowired InventoryLocationRepository locations;
 @Test void savesPrecisePickupPoint(){
  var point=new GeoPoint(new BigDecimal("43.2389490"),new BigDecimal("76.8897090"));
  var location=InventoryLocation.create(UUID.randomUUID(),"ALA-01","Магазин Алматы","KZ-ALA","Алматы",
      "Алматы","Абая 1",LocationType.SHOP,point,true);
  var id=locations.saveAndFlush(location).getId();
  var actual=locations.findById(id).orElseThrow();
  assertThat(actual.getPoint()).isNotNull();
  assertThat(actual.getPoint().latitude()).isEqualByComparingTo(point.latitude());
  assertThat(actual.getPoint().longitude()).isEqualByComparingTo(point.longitude());
 }
 @Test void privateWarehouseCannotBePublicPickup(){
  assertThatThrownBy(()->InventoryLocation.create(UUID.randomUUID(),"SEC","Склад","KZ-ALA",
    "Алматы","Алматы","Промзона",LocationType.WAREHOUSE,null,true)).isInstanceOf(IllegalArgumentException.class);
 }
}
