package kz.zakaz.order;
import static org.assertj.core.api.Assertions.*;
import java.math.*;import java.util.*;
import kz.zakaz.domain.types.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
@SpringBootTest @ActiveProfiles("test-pg") @Testcontainers
class OrderPostgresIT{
 @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17-alpine");
 @DynamicPropertySource static void settings(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",postgres::getJdbcUrl);
  r.add("spring.datasource.username",postgres::getUsername);
  r.add("spring.datasource.password",postgres::getPassword);
 }
 @Autowired CustomerOrderRepository repository;
 private CustomerOrder order(String number){return CustomerOrder.placed(UUID.randomUUID(),UUID.randomUUID(),number,List.of(
  new OrderLine.Draft(UUID.randomUUID(),BigDecimal.ONE,MeasurementUnit.PCS,new BigDecimal("1200.00"),"Алматы","KZ-ALA"))); }
 @Test void dbUniqueNumberConstraint(){
  repository.saveAndFlush(order("ZK-7M4P-9K2D"));
  assertThatThrownBy(()->repository.saveAndFlush(order("ZK-7M4P-9K2D"))).isInstanceOf(DataIntegrityViolationException.class);
 }
 @Test void differentNumbersPersist(){
  var g=new OrderNumberGenerator();var a=repository.saveAndFlush(order(g.generate()));var b=repository.saveAndFlush(order(g.generate()));
  assertThat(a.getPublicNumber()).isNotEqualTo(b.getPublicNumber());
 }
}