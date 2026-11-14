package kz.zakaz.intelligence.events;

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

/** ML-R3 RED: durable exposure is distinct from in-app queue delivery. */
@SpringBootTest @Testcontainers @ActiveProfiles("test-pg")
class MlR3ExposureSchemaPostgresIT {
    @Container static PostgreSQLContainer<?> pg =
        new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",pg::getJdbcUrl);
        r.add("spring.datasource.username",pg::getUsername);
        r.add("spring.datasource.password",pg::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Test void exposureAndConsentAuditAreDurableSeparatePostgresTables() {
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM ml_seller_invitation_view",Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM ml_invitation_consent_audit",Integer.class)).isZero();
    }
}
