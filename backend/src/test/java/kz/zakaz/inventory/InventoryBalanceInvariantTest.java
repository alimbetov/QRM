package kz.zakaz.inventory;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InventoryBalanceInvariantTest {
    @Test void neverReserveMoreThanAvailableAndNeverAcceptNonPositiveQuantity() {
        var balance=InventoryBalance.open(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                                          new BigDecimal("2.000"));
        balance.reserve(new BigDecimal("1.500"));
        assertThat(balance.getReserved()).isEqualByComparingTo("1.500");
        assertThat(balance.available()).isEqualByComparingTo("0.500");
        assertThatThrownBy(()->balance.reserve(new BigDecimal("0.501")))
            .isInstanceOf(InsufficientStockException.class);
        assertThatThrownBy(()->balance.reserve(BigDecimal.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(balance.getReserved()).isEqualByComparingTo("1.500");
    }
}
