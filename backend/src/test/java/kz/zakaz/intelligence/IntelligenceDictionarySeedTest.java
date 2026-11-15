package kz.zakaz.intelligence;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class IntelligenceDictionarySeedTest {
    @Test void referenceCoverageHasGoodsServicesAndDigitalAndTypedAttributes() {
        assertThat(IntelligenceDictionarySeed.CLASSES).hasSizeGreaterThanOrEqualTo(20);
        assertThat(IntelligenceDictionarySeed.LANGUAGES).contains("kk","ru","en");
        assertThat(IntelligenceDictionarySeed.PILOT_CITIES).contains("ALMATY","ASTANA");
        assertThat(IntelligenceDictionarySeed.definitions()).hasSizeGreaterThanOrEqualTo(20);
        assertThat(IntelligenceDictionarySeed.definitions().get("COLOR").allowedClasses())
            .contains("PHONE","CLOTHING","FURNITURE");
        assertThat(IntelligenceDictionarySeed.definitions().get("MATERIAL").allowedValues())
            .contains("COPPER","COTTON");
    }
    @Test void numericValuesNormalizeButOpaqueCodesDoNotBecomeNumbers() {
        assertThat(IntelligenceDictionarySeed.canonicalNumeric("PHONE","STORAGE_GB","0256.00"))
            .isEqualTo("256");
        assertThat(IntelligenceDictionarySeed.canonicalNumeric("CLOTHING","COLOR","BLACK"))
            .isEqualTo("BLACK");
        assertThatThrownBy(()->IntelligenceDictionarySeed.validate("CLOTHING","STORAGE_GB","256"))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void sizeAndLanguageRemainClassScoped() {
        IntelligenceDictionarySeed.validate("FOOTWEAR","FOOTWEAR_SIZE","EU_42");
        IntelligenceDictionarySeed.validate("ELECTRICIAN_SERVICE","SERVICE_LANGUAGE","kk");
        assertThatThrownBy(()->IntelligenceDictionarySeed.validate("CLOTHING","FOOTWEAR_SIZE","EU_42"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->IntelligenceDictionarySeed.validate("PHONE","SERVICE_LANGUAGE","kk"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->IntelligenceDictionarySeed.validate("PHONE","COLOR","NONEXISTENT"))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsNegativeMeasurements() {
        assertThatThrownBy(()->IntelligenceDictionarySeed.validate("PHONE","STORAGE_GB","-128"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
