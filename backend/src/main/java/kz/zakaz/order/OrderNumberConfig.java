package kz.zakaz.order;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration(proxyBeanMethods=false)
class OrderNumberConfig {
 @Bean OrderNumberGenerator orderNumberGenerator() {return new OrderNumberGenerator();}
}