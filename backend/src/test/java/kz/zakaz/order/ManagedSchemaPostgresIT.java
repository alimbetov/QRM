package kz.zakaz.order;

import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test-pg") @Testcontainers
class ManagedSchemaPostgresIT {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Test void flywayOwnsEntireBaselineAndHibernateOnlyValidates() {
        var applied=jdbc.queryForList(
            "select version from flyway_schema_history where success=true order by installed_rank",String.class);
        assertThat(applied).contains("1","2","3","4");
        for(var table:List.of("customer_order","order_line","inventory_location",
               "catalog_listing","catalog_attribute_enum_option","seller_membership")) {
            assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema='public' and table_name=?",
                Integer.class,table)).isEqualTo(1);
        }
    }
}
