package kz.zakaz.location;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
class GeoPointTest{
 @Test void acceptsKazakhstanCoordinate(){assertThat(new GeoPoint(new BigDecimal("43.238949"),new BigDecimal("76.889709")).latitude()).isEqualByComparingTo("43.238949");}
 @Test void rejectsInvalidCoordinate(){assertThatThrownBy(()->new GeoPoint(new BigDecimal("91"),BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);}
}