package kz.zakaz.catalog;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;

/**
 * CAT-R1B four-vertical PostgreSQL fixtures against the actual Flyway schema.
 * The HCC codes below are isolated test-only codes and are NEVER catalog seeds.
 *
 * These tests qualify only CURRENT SKU facts and the service type boundary.
 * Lot/expiry, service booking, multilingual search and production taxonomies
 * are separate feature gates and are not claimed by these fixtures.
 */
@SpringBootTest
@ActiveProfiles("test-pg")
@Testcontainers
class CatalogR1BFourPilotPostgresIT {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogSkuFactsResolver facts;

    private static UUID id() { return UUID.randomUUID(); }

    private record Item(UUID category,UUID product,UUID seller,UUID listing,UUID sku) {}

    private UUID category(String code,String productType,String label) {
        UUID id=id();
        jdbc.update("""
            INSERT INTO catalog_category(id,hcc_code,product_type,label,active)
            VALUES (?,?,?,?,true)
            """,id,code,productType,label);
        return id;
    }
    private UUID product(UUID category,String name) {
        UUID key=id();
        jdbc.update("INSERT INTO catalog_product(id,category_id,canonical_name) VALUES (?,?,?)",
            key,category,name);
        return key;
    }
    private Item item(UUID category,UUID product,String code,String salesUnit) {
        UUID seller=id(),listing=id(),sku=id();
        jdbc.update("""
            INSERT INTO catalog_listing(id,seller_id,product_id,status)
            VALUES (?,?,?,'DRAFT')
            """,listing,seller,product);
        jdbc.update("""
            INSERT INTO catalog_sku(id,listing_id,seller_sku_code,tracking_mode,sales_unit)
            VALUES (?,?,?,'TRACKED',?)
            """,sku,listing,code,salesUnit);
        return new Item(category,product,seller,listing,sku);
    }
    private UUID variant(Item item,String code,String salesUnit) {
        UUID sku=id();
        jdbc.update("""
            INSERT INTO catalog_sku(id,listing_id,seller_sku_code,tracking_mode,sales_unit)
            VALUES (?,?,?,'TRACKED',?)
            """,sku,item.listing(),code,salesUnit);
        return sku;
    }
    private UUID attribute(String code,String kind,String unit,BigDecimal min,BigDecimal max) {
        UUID attr=id();
        jdbc.update("""
            INSERT INTO catalog_attribute_definition
                (id,code,kind,canonical_unit,active,min_decimal,max_decimal)
            VALUES (?,?,?,?,true,?,?)
            """,attr,code,kind,unit,min,max);
        return attr;
    }
    private void option(UUID attribute,String code) {
        jdbc.update("""
            INSERT INTO catalog_attribute_enum_option(id,attribute_id,code,active)
            VALUES (?,?,?,true)
            """,id(),attribute,code);
    }
    private void rule(UUID category,UUID attribute,boolean required) {
        jdbc.update("""
            INSERT INTO catalog_category_attribute_rule
                (id,category_id,attribute_id,required,filterable)
            VALUES (?,?,?,?,true)
            """,id(),category,attribute,required);
    }
    private void fact(UUID sku,UUID attribute,String value) {
        jdbc.update("""
            INSERT INTO catalog_sku_attribute_value
                (id,sku_id,attribute_id,canonical_value,definition_revision)
            VALUES (?,?,?,?,1)
            """,id(),sku,attribute,value);
    }
    private void publish(Item item) {
        // This isolates published fact read behavior, not QR or seller HTTP E2E.
        // QR-first publication is covered separately by existing integration tests.
        jdbc.update("UPDATE catalog_listing SET status='PUBLIC' WHERE id=?",item.listing());
    }

    @Test void fruitsKeepCultivarAndPackageMassAtSkuScopeWithoutGuessingBoxToKg() {
        UUID category=category("71000000000000000000","PHYSICAL","Fresh apples");
        UUID product=product(category,"Fresh apples");
        UUID variety=attribute("PILOT_VARIETY_CODE","ENUM",null,null,null);
        UUID packageMass=attribute("PILOT_PACKAGE_NET_MASS_KG","DECIMAL","KG",
            BigDecimal.ZERO,new BigDecimal("100"));
        rule(category,variety,true);rule(category,packageMass,false);
        option(variety,"GALA");option(variety,"FUJI");
        Item offer=item(category,product,"GALA-BOX","PCS");
        UUID fuji=variant(offer,"FUJI-BOX","PCS");
        fact(offer.sku(),variety,"GALA");
        fact(offer.sku(),packageMass,"12");
        fact(fuji,variety,"FUJI");
        publish(offer);

        var gala=facts.resolveForSeller(offer.seller(),offer.sku());
        var other=facts.resolveForSeller(offer.seller(),fuji);
        assertThat(gala.attributes()).containsEntry("PILOT_VARIETY_CODE","GALA")
            .containsEntry("PILOT_PACKAGE_NET_MASS_KG","12");
        assertThat(other.attributes()).containsEntry("PILOT_VARIETY_CODE","FUJI")
            .doesNotContainKey("PILOT_PACKAGE_NET_MASS_KG");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM catalog_unit WHERE code='BOX'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT sales_unit FROM catalog_sku WHERE id=?",String.class,offer.sku()))
            .isEqualTo("PCS");
        // KG price or checkout conversion must NOT be invented from unit PCS.
    }

    @Test void foodHasExactVolumeFatAndSignedTemperatureBoundedInPostgres() {
        UUID category=category("72000000000000000000","PHYSICAL","Pasteurized milk");
        UUID product=product(category,"Milk");
        UUID volume=attribute("PILOT_PACK_VOLUME_L","DECIMAL","L",BigDecimal.ZERO,new BigDecimal("10"));
        UUID fat=attribute("PILOT_FAT_PERCENT","DECIMAL","PERCENT",BigDecimal.ZERO,new BigDecimal("100"));
        UUID temperature=attribute("PILOT_STORAGE_TEMP_MIN_C","DECIMAL","CEL",
            new BigDecimal("-40"),new BigDecimal("30"));
        rule(category,volume,true);rule(category,fat,true);rule(category,temperature,false);
        Item milk=item(category,product,"MILK-1L","PCS");
        UUID small=variant(milk,"MILK-900ML","PCS");
        fact(milk.sku(),volume,"1");
        fact(milk.sku(),fat,"3.2");
        fact(milk.sku(),temperature,"-18");
        fact(small,volume,"0.9");fact(small,fat,"3.2");
        assertThatThrownBy(() -> fact(small,temperature,"-41"))
            .isInstanceOf(DataAccessException.class);
        publish(milk);

        assertThat(facts.resolveForSeller(milk.seller(),milk.sku()).attributes())
            .containsEntry("PILOT_PACK_VOLUME_L","1")
            .containsEntry("PILOT_STORAGE_TEMP_MIN_C","-18");
        assertThat(facts.resolveForSeller(milk.seller(),small).attributes())
            .containsEntry("PILOT_PACK_VOLUME_L","0.9");
        assertThat(jdbc.queryForObject(
            "SELECT dimension FROM catalog_unit WHERE code='L'",String.class))
            .isEqualTo("VOLUME");
        // Expiry, batch provenance and cold-chain enforcement require LOT (not present).
    }

    @Test void electricalPhysicalSkuAndInstallationServiceUseDistinctTypesAndFactScopes() {
        UUID goods=category("73000000000000000000","PHYSICAL","Wall sockets");
        UUID installation=category("74000000000000000000","SERVICE","Socket installation");
        UUID product=product(goods,"Wall socket 16 A");
        UUID service=product(installation,"Socket installation");
        UUID amperes=attribute("PILOT_RATED_CURRENT_A","DECIMAL","A",
            BigDecimal.ZERO,new BigDecimal("63"));
        rule(goods,amperes,true);
        Item socket=item(goods,product,"SOCKET-16A","PCS");
        fact(socket.sku(),amperes,"16");
        publish(socket);
        assertThat(facts.resolveForSeller(socket.seller(),socket.sku()).attributes())
            .containsEntry("PILOT_RATED_CURRENT_A","16");

        UUID workType=id();
        jdbc.update("""
            INSERT INTO catalog_attribute_definition
                (id,code,kind,active,fact_scope)
            VALUES (?,'PILOT_WORK_TYPE','ENUM',true,'SERVICE_SPEC')
            """,workType);
        assertThat(jdbc.queryForObject(
            "SELECT product_type FROM catalog_category WHERE id=?",String.class,installation))
            .isEqualTo("SERVICE");
        assertThat(jdbc.queryForObject(
            "SELECT category_id FROM catalog_product WHERE id=?",UUID.class,service))
            .isEqualTo(installation);
        assertThatThrownBy(() -> fact(socket.sku(),workType,"SOCKET_INSTALL"))
            .isInstanceOf(DataAccessException.class);
        // Service execution/capacity/booking is deliberately not a tracked SKU.
    }

    @Test void electronicsDoNotCollapseMemoryVariantsAndRejectNegativeStorage() {
        UUID category=category("75000000000000000000","PHYSICAL","Smartphones");
        UUID product=product(category,"Phone model A");
        UUID storage=attribute("PILOT_STORAGE_GB","DECIMAL","GB",
            BigDecimal.ZERO,new BigDecimal("2048"));
        UUID color=attribute("PILOT_COLOR","ENUM",null,null,null);
        rule(category,storage,true);rule(category,color,false);
        option(color,"BLACK");option(color,"WHITE");
        Item offer=item(category,product,"PHONE-256","PCS");
        UUID small=variant(offer,"PHONE-128","PCS");
        fact(offer.sku(),storage,"256");fact(offer.sku(),color,"BLACK");
        fact(small,storage,"128");fact(small,color,"WHITE");
        assertThatThrownBy(() -> fact(small,storage,"-1"))
            .isInstanceOf(DataAccessException.class);
        publish(offer);

        assertThat(facts.resolveForSeller(offer.seller(),offer.sku()).attributes())
            .containsEntry("PILOT_STORAGE_GB","256").containsEntry("PILOT_COLOR","BLACK");
        assertThat(facts.resolveForSeller(offer.seller(),small).attributes())
            .containsEntry("PILOT_STORAGE_GB","128").containsEntry("PILOT_COLOR","WHITE");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM catalog_product WHERE id=?",Integer.class,product))
            .isEqualTo(1);
    }
}
