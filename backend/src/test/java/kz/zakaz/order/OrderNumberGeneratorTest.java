package kz.zakaz.order;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class OrderNumberGeneratorTest{
 @Test void format(){var g=new OrderNumberGenerator();for(int i=0;i<1000;i++)assertThat(OrderNumberGenerator.isValid(g.generate())).isTrue();}
 @Test void rejectsAmbiguous(){assertThat(OrderNumberGenerator.isValid("ZK-OOOO-1111")).isFalse();}
}