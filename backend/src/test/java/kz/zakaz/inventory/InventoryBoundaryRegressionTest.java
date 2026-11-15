package kz.zakaz.inventory;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Fast domain regressions; concurrent PostgreSQL behavior is covered separately. */
class InventoryBoundaryRegressionTest {
    private static final UUID SELLER = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID SKU = UUID.randomUUID();

    @Test void partialReservationAndReleasePreserveConservationOfStock() {
        var balance = InventoryBalance.open(SELLER, LOCATION, SKU, new BigDecimal("10.000"));
        balance.reserve(new BigDecimal("2.250"));
        balance.reserve(new BigDecimal("1.750"));
        assertThat(balance.getOnHand()).isEqualByComparingTo("10");
        assertThat(balance.getReserved()).isEqualByComparingTo("4");
        assertThat(balance.available()).isEqualByComparingTo("6");
        balance.release(new BigDecimal("1.250"));
        assertThat(balance.getReserved()).isEqualByComparingTo("2.750");
        assertThat(balance.available()).isEqualByComparingTo("7.250");
        balance.receive(new BigDecimal("3.000"));
        assertThat(balance.getOnHand()).isEqualByComparingTo("13");
        assertThat(balance.available()).isEqualByComparingTo("10.250");
    }

    @Test void failedOperationsLeaveBalanceUnchanged() {
        var balance = InventoryBalance.open(SELLER, LOCATION, SKU, new BigDecimal("2.000"));
        balance.reserve(new BigDecimal("1.250"));
        assertThatThrownBy(() -> balance.reserve(new BigDecimal("0.751")))
            .isInstanceOf(InsufficientStockException.class);
        assertThatThrownBy(() -> balance.release(new BigDecimal("1.251")))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> balance.receive(new BigDecimal("0.0001")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(balance.getOnHand()).isEqualByComparingTo("2.000");
        assertThat(balance.getReserved()).isEqualByComparingTo("1.250");
        assertThat(balance.available()).isEqualByComparingTo("0.750");
    }

    @Test void quantitiesEnforceDatabaseScalePrecisionAndPositiveReservations() {
        assertThatThrownBy(() -> InventoryBalance.open(SELLER, LOCATION, SKU, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InventoryBalance.open(SELLER, LOCATION, SKU, new BigDecimal("-0.001")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InventoryBalance.open(SELLER, LOCATION, SKU, new BigDecimal("1.0001")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InventoryBalance.open(SELLER, LOCATION, SKU, new BigDecimal("12345678901234567890")))
            .isInstanceOf(IllegalArgumentException.class);
        var balance = InventoryBalance.open(SELLER, LOCATION, SKU, BigDecimal.ZERO);
        assertThatThrownBy(() -> balance.reserve(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> balance.receive(new BigDecimal("12345678901234567890")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(balance.getOnHand()).isEqualByComparingTo("0");
    }

    @Test void reservationCannotBeReleasedTwiceOrCreatedWithInvalidQuantity() {
        var reservation = InventoryReservation.hold(UUID.randomUUID(), UUID.randomUUID(),
            SELLER, LOCATION, SKU, new BigDecimal("1.500"));
        assertThat(reservation.getStatus()).isEqualTo("RESERVED");
        assertThat(reservation.getQuantity()).isEqualByComparingTo("1.500");
        reservation.release();
        assertThat(reservation.getStatus()).isEqualTo("RELEASED");
        assertThatThrownBy(reservation::release).isInstanceOf(IllegalStateException.class);
        for (var amount : new BigDecimal[] {BigDecimal.ZERO, new BigDecimal("-1"),
                new BigDecimal("0.0001"), new BigDecimal("12345678901234567890")}) {
            assertThatThrownBy(() -> InventoryReservation.hold(UUID.randomUUID(), UUID.randomUUID(),
                SELLER, LOCATION, SKU, amount)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
