package kz.zakaz.catalog;

import org.junit.jupiter.api.Test;
import kz.zakaz.domain.types.ProductType;
import static org.assertj.core.api.Assertions.*;

class CatalogR1B2HccParentTest {
    @Test void parentMustBeImmediateAncestorNotSkippedTier() {
        var parent=Category.create("01020000000000000000",ProductType.PHYSICAL,"Electronics",null);
        assertThatThrownBy(()->Category.create("01020030000100000000",ProductType.PHYSICAL,"Grandchild",parent))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(()->Category.create("01020030000000000000",ProductType.PHYSICAL,"Child",parent))
            .doesNotThrowAnyException();
    }
}
