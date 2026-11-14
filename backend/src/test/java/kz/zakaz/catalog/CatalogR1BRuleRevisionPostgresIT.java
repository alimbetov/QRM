package kz.zakaz.catalog;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

/**
 * CAT-R1B RED-first acceptance tests. Intentionally fail on the current
 * Flyway V30 schema: the category rule history and publication snapshot
 * are NOT implemented until independently reviewed S2 contract approval.
 *
 * Use the RED CI SHA as proof, then hold the tests without pretending
 * category-rule history exists in production.
 */
@SpringBootTest @ActiveProfiles("test-pg") @Testcontainers
class CatalogR1BRuleRevisionPostgresIT {
    @Container static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);
        r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogSkuFactsResolver facts;
    private static UUID id(){return UUID.randomUUID();}
    private static final AtomicInteger rootCode = new AtomicInteger(76);

    private record Fixture(UUID category,UUID product,UUID listing,
                           UUID sku,UUID seller,UUID attr) {}

    private Fixture publishedPhone() {
        UUID category=id(),product=id(),listing=id(),sku=id(),seller=id(),storage=id();
        String hcc=String.format("%02d",rootCode.getAndIncrement())+"000000000000000000";
        jdbc.update("""
          INSERT INTO catalog_category(id,hcc_code,product_type,label,active)
          VALUES (?,?,'PHYSICAL','Pilot phone',true)
          """,category,hcc);
        jdbc.update("""
          INSERT INTO catalog_product(id,category_id,canonical_name)
          VALUES (?,?,'Pilot phone')
          """,product,category);
        jdbc.update("""
          INSERT INTO catalog_listing(id,seller_id,product_id,status)
          VALUES (?,?,?,'DRAFT')
          """,listing,seller,product);
        jdbc.update("""
          INSERT INTO catalog_sku(id,listing_id,seller_sku_code,tracking_mode,sales_unit)
          VALUES (?,?,'PHONE-256','TRACKED','PCS')
          """,sku,listing);
        jdbc.update("""
          INSERT INTO catalog_attribute_definition(id,code,kind,canonical_unit,active)
          VALUES (?,'PILOT_R1B_STORAGE_GB','DECIMAL','GB',true)
          """,storage);
        jdbc.update("""
          INSERT INTO catalog_category_attribute_rule
            (id,category_id,attribute_id,required,filterable)
          VALUES (?,?,?,true,true)
          """,id(),category,storage);
        jdbc.update("""
          INSERT INTO catalog_sku_attribute_value
            (id,sku_id,attribute_id,canonical_value,definition_revision)
          VALUES (?,?,?,'256',1)
          """,id(),sku,storage);
        // A legacy already-public offer; no forced rewriting of historical facts.
        jdbc.update("UPDATE catalog_listing SET status='PUBLIC' WHERE id=?",listing);
        return new Fixture(category,product,listing,sku,seller,storage);
    }

    @Test void alreadyPublishedSkuMustStayReadableAfterFutureRequiredRule() {
        Fixture f=publishedPhone();
        assertThat(facts.resolveForSeller(f.seller(),f.sku()).attributes())
            .containsEntry("PILOT_R1B_STORAGE_GB","256");
        UUID color=id();
        jdbc.update("""
          INSERT INTO catalog_attribute_definition(id,code,kind,active)
          VALUES (?,'PILOT_R1B_COLOR','ENUM',true)
          """,color);
        jdbc.update("""
          INSERT INTO catalog_category_attribute_rule
            (id,category_id,attribute_id,required,filterable)
          VALUES (?,?,?,true,true)
          """,id(),f.category(),color);

        // RED on V30: CatalogSkuFactsResolver uses latest required rules,
        // retrospectively invalidating an already-published unchanged SKU.
        assertThatCode(() -> facts.resolveForSeller(f.seller(),f.sku()))
            .doesNotThrowAnyException();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM catalog_sku_attribute_value WHERE sku_id=?",
            Integer.class,f.sku())).isEqualTo(1);
    }

    @Test void ruleMutationMustCreateReproducibleImmutableCategorySchemaRevision() {
        Fixture f=publishedPhone();
        long oldRevision=jdbc.queryForObject("""
            SELECT attribute_schema_revision FROM catalog_category WHERE id=?
            """,Long.class,f.category());
        UUID optional=id();
        jdbc.update("""
          INSERT INTO catalog_attribute_definition(id,code,kind,active)
          VALUES (?,'PILOT_R1B_OPTIONAL','BOOLEAN',true)
          """,optional);
        jdbc.update("""
          INSERT INTO catalog_category_attribute_rule
              (id,category_id,attribute_id,required,filterable)
          VALUES (?,?,?,false,true)
          """,id(),f.category(),optional);

        long next=jdbc.queryForObject("""
            SELECT attribute_schema_revision FROM catalog_category WHERE id=?
            """,Long.class,f.category());
        assertThat(next).isGreaterThan(oldRevision);
        assertThat(jdbc.queryForObject("""
          SELECT required FROM catalog_category_rule_revision
          WHERE category_id=? AND revision=? AND attribute_id=?
          """,Boolean.class,f.category(),oldRevision,f.attr())).isTrue();
        assertThat(jdbc.queryForObject("""
          SELECT count(*) FROM catalog_category_rule_revision
          WHERE category_id=? AND revision=? AND attribute_id=?
          """,Integer.class,f.category(),oldRevision,optional)).isZero();
        assertThat(jdbc.queryForObject("""
          SELECT count(*) FROM catalog_category_rule_revision
          WHERE category_id=? AND revision=? AND attribute_id=?
          """,Integer.class,f.category(),next,optional)).isEqualTo(1);
    }

    @Test void oldRuleHistoryCannotBeOverwrittenByDirectSql() {
        Fixture f=publishedPhone();
        long pinned=jdbc.queryForObject("""
          SELECT attribute_schema_revision FROM catalog_category WHERE id=?
          """,Long.class,f.category());
        assertThatThrownBy(() -> jdbc.update("""
          UPDATE catalog_category_rule_revision SET required=false
          WHERE category_id=? AND revision=? AND attribute_id=?
          """,f.category(),pinned,f.attr()))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("""
          SELECT required FROM catalog_category_rule_revision
          WHERE category_id=? AND revision=? AND attribute_id=?
          """,Boolean.class,f.category(),pinned,f.attr())).isTrue();
    }
}
