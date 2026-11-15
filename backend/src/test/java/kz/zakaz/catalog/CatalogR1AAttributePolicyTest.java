package kz.zakaz.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import kz.zakaz.domain.types.ProductType;
import kz.zakaz.domain.types.StockTrackingMode;

import static org.assertj.core.api.Assertions.*;

class CatalogR1AAttributePolicyTest {
    @Test void legacyNonNegativeNumericRuleRemainsStrict() {
        var storage=AttributeDefinition.create("STORAGE_GB",AttributeDefinition.Kind.DECIMAL,"GB");
        assertThat(storage.getMinDecimal()).isEqualByComparingTo("0");
        assertThat(storage.getSchemaRevision()).isEqualTo(1L);
        assertThat(storage.canonicalize("0256.00")).isEqualTo("256");
        assertThatThrownBy(() -> storage.canonicalize("-1"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void signedTemperatureHasExplicitBoundsAndEachChangeAdvancesRevision() {
        var temp=AttributeDefinition.createDecimal("STORAGE_TEMP_MIN_C","CEL",
            new BigDecimal("-40"),new BigDecimal("30"));
        assertThat(temp.canonicalize("-018.0")).isEqualTo("-18");
        assertThatThrownBy(() -> temp.canonicalize("-41")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> temp.canonicalize("31")).isInstanceOf(IllegalArgumentException.class);
        temp.reviseDecimalBounds(BigDecimal.ZERO,new BigDecimal("30"));
        assertThat(temp.getSchemaRevision()).isEqualTo(2L);
        assertThatThrownBy(() -> temp.canonicalize("-18")).isInstanceOf(IllegalArgumentException.class);
        assertThat(temp.isCanonicalForm("-18")).isTrue();
        assertThatThrownBy(() -> temp.reviseDecimalBounds(BigDecimal.ZERO,new BigDecimal("30")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void dateIsStrictAndInvalidCalendarDaysNeverNormalize() {
        var date=AttributeDefinition.create("PACK_DATE",AttributeDefinition.Kind.DATE,null);
        assertThat(date.canonicalize("2026-11-14")).isEqualTo("2026-11-14");
        for(String bad:List.of("2026-02-30","2026-2-4","not-a-date","2026-13-14")) {
            assertThatThrownBy(() -> date.canonicalize(bad))
                .as("date "+bad).isInstanceOf(IllegalArgumentException.class);
            assertThat(date.isCanonicalForm(bad)).isFalse();
        }
    }

    @Test void historicalSkuValueKeepsPinnedRevisionAfterDefinitionBoundsChange() {
        var category=Category.create("01020030000000000000",ProductType.PHYSICAL,"Pilot",null);
        var product=CanonicalProduct.create(category,"Pilot item");
        var listing=SellerListing.draft(UUID.randomUUID(),product);
        var sku=SellerSku.create(listing,"SKU-1",StockTrackingMode.TRACKED);
        var temp=AttributeDefinition.createDecimal("TEMP_C","CEL",
            new BigDecimal("-40"),new BigDecimal("30"));
        var rule=CategoryAttributeRule.define(category,temp,true,true);
        var fact=SkuAttributeValue.of(sku,rule,"-18.0");
        assertThat(fact.getDefinitionRevision()).isEqualTo(1L);
        temp.reviseDecimalBounds(BigDecimal.ZERO,new BigDecimal("30"));
        assertThat(fact.getDefinitionRevision()).isEqualTo(1L);
        assertThat(fact.getCanonicalValue()).isEqualTo("-18");
        assertThatThrownBy(() -> CatalogPublicationPolicy.verify(category,List.of(rule),List.of(fact)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stale");
        // An already published SKU must remain readable under the older pinned revision.
        listing.publish();
        assertThatCode(() -> CatalogPublicationPolicy.verify(category,List.of(rule),List.of(fact)))
            .doesNotThrowAnyException();
    }
}
