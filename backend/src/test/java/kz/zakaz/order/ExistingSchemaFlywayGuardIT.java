package kz.zakaz.order;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ExistingSchemaFlywayGuardIT {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void existingUnmanagedSchemaNeverGetsAutoBaselined() throws Exception {
        try(var connection=DriverManager.getConnection(
                postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
            var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE legacy_customer_data(id uuid PRIMARY KEY)");
        }
        Flyway flyway=Flyway.configure()
            .dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .load();
        assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class);
    }
}
