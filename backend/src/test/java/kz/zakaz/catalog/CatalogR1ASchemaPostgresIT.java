package kz.zakaz.catalog;

import java.math.BigDecimal;
import java.util.UUID;
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
 * CAT-R1A: real PostgreSQL contract tests, intentionally introduced RED before V28.
 * No fake category seeds, no modification of existing Order/QR/Inventory contracts.
 */
@SpringBootTest @ActiveProfiles("test-pg") @Testcontainers
class CatalogR1ASchemaPostgresIT {
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void dataSource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }
    @Autowired JdbcTemplate jdbc;

    private static UUID id() { return UUID.randomUUID(); }
    private UUID attribute(String code, String kind, String unit, BigDecimal min, BigDecimal max) {
        UUID key = id();
        jdbc.update("""
            INSERT INTO catalog_attribute_definition
              (id,code,kind,canonical_unit,active,min_decimal,max_decimal,schema_revision,fact_scope)
            VALUES (?,?,?,?,true,?,?,1,'SKU')
            """, key, code, kind, unit, min, max);
        return key;
    }
    private record Sku(UUID skuId, UUID listingId) {}
    private Sku draftSku(String hcc) {
        UUID category = id(), product = id(), listing = id(), sku = id();
        jdbc.update("INSERT INTO catalog_category(id,hcc_code,product_type,label,active) VALUES (? ,?,'PHYSICAL','CAT-R1A',true)", category,hcc);
        jdbc.update("INSERT INTO catalog_product(id,category_id,canonical_name) VALUES (?,?,'CAT-R1A object')", product,category);
        jdbc.update("INSERT INTO catalog_listing(id,seller_id,product_id,status) VALUES (?,?,?,'DRAFT')", listing,id(),product);
        jdbc.update("INSERT INTO catalog_sku(id,listing_id,seller_sku_code,tracking_mode,sales_unit) VALUES (?,?,'CAT-R1A','TRACKED','PCS')", sku,listing);
        return new Sku(sku,listing);
    }

    @Test void negativeTemperatureAllowedByBoundedPolicyButLegacyStorageRemainsNonNegative() {
        UUID temperature = attribute("CAT_R1A_STORAGE_TEMP_C","DECIMAL","CEL",new BigDecimal("-40"),new BigDecimal("30"));
        UUID storage = attribute("CAT_R1A_STORAGE_GB","DECIMAL","GB",BigDecimal.ZERO,null);
        var sku = draftSku("51000000000000000000");
        jdbc.update("""
          INSERT INTO catalog_sku_attribute_value(id,sku_id,attribute_id,canonical_value,definition_revision)
          VALUES (?,?,?,'-18',1)
          """,id(),sku.skuId(),temperature);
        assertThat(jdbc.queryForObject(
            "SELECT canonical_value FROM catalog_sku_attribute_value WHERE sku_id=? AND attribute_id=?",
            String.class,sku.skuId(),temperature)).isEqualTo("-18");
        assertThatThrownBy(() -> jdbc.update("""
          INSERT INTO catalog_sku_attribute_value(id,sku_id,attribute_id,canonical_value,definition_revision)
          VALUES (?,?,?,'-1',1)
          """,id(),sku.skuId(),storage)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
          INSERT INTO catalog_sku_attribute_value(id,sku_id,attribute_id,canonical_value,definition_revision)
          VALUES (?,?,?,'31',1)
          """,id(),sku.skuId(),temperature)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void schemaRevisionIsAppendOnlyAndDraftValuePinsHistoricalRevision() {
        UUID attribute=attribute("CAT_R1A_REVISIONED_TEMP","DECIMAL","CEL",BigDecimal.ZERO,new BigDecimal("50"));
        var sku=draftSku("52000000000000000000");
        UUID fact=id();
        jdbc.update("""
          INSERT INTO catalog_sku_attribute_value(id,sku_id,attribute_id,canonical_value,definition_revision)
          VALUES (?,?,?,'12',1)
          """,fact,sku.skuId(),attribute);
        jdbc.update("""
          UPDATE catalog_attribute_definition
          SET min_decimal=-40,schema_revision=2 WHERE id=?
          """,attribute);
        assertThat(jdbc.queryForObject("""
           SELECT count(*) FROM catalog_attribute_schema_revision WHERE attribute_id=?
           """,Integer.class,attribute)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
           SELECT definition_revision FROM catalog_sku_attribute_value WHERE id=?
           """,Long.class,fact)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
           SELECT min_decimal FROM catalog_attribute_schema_revision
           WHERE attribute_id=? AND revision=1
           """,BigDecimal.class,attribute)).isEqualByComparingTo("0");
        assertThatThrownBy(() -> jdbc.update("""
          UPDATE catalog_attribute_schema_revision SET min_decimal=-100
          WHERE attribute_id=? AND revision=1
          """,attribute)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
          UPDATE catalog_attribute_definition SET min_decimal=-50 WHERE id=?
          """,attribute)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void strictCalendarDateAndFactScopeCannotBeBypassedByDirectSql() {
        UUID date = attribute("CAT_R1A_PACK_DATE","DATE",null,null,null);
        var sku=draftSku("53000000000000000000");
        jdbc.update("""
          INSERT INTO catalog_sku_attribute_value(id,sku_id,attribute_id,canonical_value,definition_revision)
          VALUES (?,?,?,'2026-11-14',1)
          """,id(),sku.skuId(),date);
        assertThatThrownBy(() -> jdbc.update("""
          INSERT INTO catalog_sku_attribute_value(id,sku_id,attribute_id,canonical_value,definition_revision)
          VALUES (?,?,?,'2026-02-30',1)
          """,id(),sku.skuId(),date)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        UUID lotAttribute=id();
        jdbc.update("""
          INSERT INTO catalog_attribute_definition
              (id,code,kind,active,schema_revision,fact_scope)
          VALUES (?,'CAT_R1A_LOT_ONLY','TEXT',true,1,'LOT')
          """,lotAttribute);
        assertThatThrownBy(() -> jdbc.update("""
          INSERT INTO catalog_sku_attribute_value(id,sku_id,attribute_id,canonical_value,definition_revision)
          VALUES (?,?,?,'LOT-1',1)
          """,id(),sku.skuId(),lotAttribute)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void hierarchicalCodeMustMatchImmediateParentAndCannotBeRecoded() {
        UUID parent=id(),child=id();
        jdbc.update("""
          INSERT INTO catalog_category(id,hcc_code,product_type,label,active)
          VALUES (?,'54000000000000000000','PHYSICAL','Root',true)
          """,parent);
        jdbc.update("""
          INSERT INTO catalog_category(id,hcc_code,product_type,parent_id,label,active)
          VALUES (?,'54010000000000000000','PHYSICAL',?,'Child',true)
          """,child,parent);
        assertThatThrownBy(() -> jdbc.update("""
          INSERT INTO catalog_category(id,hcc_code,product_type,parent_id,label,active)
          VALUES (?,'55010000000000000000','PHYSICAL',?,'Wrong branch',true)
          """,id(),parent)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
          INSERT INTO catalog_category(id,hcc_code,product_type,parent_id,label,active)
          VALUES (?,'54020000000000000000','SERVICE',?,'Wrong type',true)
          """,id(),parent)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
          UPDATE catalog_category SET hcc_code='54030000000000000000' WHERE id=?
          """,child)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
          UPDATE catalog_category SET parent_id=? WHERE id=?
          """,child,parent)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        jdbc.update("UPDATE catalog_category SET label='Renamed' WHERE id=?",child);
        assertThat(jdbc.queryForObject(
          "SELECT label FROM catalog_category WHERE id=?",String.class,child)).isEqualTo("Renamed");
    }

    @Test void unitRegistrySupportsOnlyApprovedExactDimensionConversions() {
        BigDecimal grams=jdbc.queryForObject("SELECT factor_to_base FROM catalog_unit WHERE code='G'",BigDecimal.class);
        BigDecimal kilos=jdbc.queryForObject("SELECT factor_to_base FROM catalog_unit WHERE code='KG'",BigDecimal.class);
        assertThat(new BigDecimal("1000").multiply(grams).divide(kilos))
            .isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT dimension FROM catalog_unit WHERE code='KG'",String.class))
            .isEqualTo("MASS");
        assertThat(jdbc.queryForObject("SELECT dimension FROM catalog_unit WHERE code='L'",String.class))
            .isEqualTo("VOLUME");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_unit WHERE code='BOX'",Integer.class))
            .isZero();
        assertThatThrownBy(() -> jdbc.update("""
          UPDATE catalog_unit SET dimension='VOLUME' WHERE code='KG'
          """)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
