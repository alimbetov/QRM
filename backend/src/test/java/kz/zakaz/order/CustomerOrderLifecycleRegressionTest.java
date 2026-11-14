package kz.zakaz.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kz.zakaz.domain.types.MeasurementUnit;
import kz.zakaz.domain.types.OrderStatus;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CustomerOrderLifecycleRegressionTest {
    private static final UUID SELLER = UUID.randomUUID();
    private static final UUID BUYER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

    private static OrderLine.Draft line(String quantity, MeasurementUnit unit, String unitPrice) {
        return new OrderLine.Draft(UUID.randomUUID(),new BigDecimal(quantity),unit,
            new BigDecimal(unitPrice),"Almaty","KZ-ALA");
    }

    @Test void acceptedOrderSealsPriceDeliveryAndSnapshotAtCreation() {
        UUID accepted = UUID.randomUUID();
        var order = CustomerOrder.placedFromBid(SELLER,BUYER,"ZK-2345-6789",
            List.of(line("2",MeasurementUnit.PCS,"120.50"),
                    line("1.500",MeasurementUnit.KG,"10.00")),
            accepted,new BigDecimal("20.00"),NOW);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("276.00");
        assertThat(order.getDeliveryFee()).isEqualByComparingTo("20.00");
        assertThat(order.getAcceptedBidId()).isEqualTo(accepted);
        assertThat(order.getCurrencyCode()).isEqualTo("KZT");
        assertThat(order.getSellerId()).isEqualTo(SELLER);
        assertThat(order.getBuyerId()).isEqualTo(BUYER);
        assertThat(order.getLines()).hasSize(2);
        assertThat(order.getLines().getFirst().getOriginCitySnapshot()).isEqualTo("Almaty");
        assertThatThrownBy(() -> order.getLines().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void confirmationAndCancellationAreOneWayTransitions() {
        var confirmed = CustomerOrder.placed(SELLER,BUYER,"ZK-2345-6789",
            List.of(line("1",MeasurementUnit.PCS,"1.00")));
        confirmed.confirm();
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThatThrownBy(confirmed::confirm).isInstanceOf(IllegalStateException.class);
        confirmed.cancelBySeller();
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CANCELLED_BY_SELLER);
        assertThatThrownBy(confirmed::confirm).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(confirmed::cancelBySeller).isInstanceOf(IllegalStateException.class);

        var placed = CustomerOrder.placed(SELLER,BUYER,"ZK-2345-6789",
            List.of(line("1",MeasurementUnit.PCS,"1.00")));
        placed.cancelBySeller();
        assertThat(placed.getStatus()).isEqualTo(OrderStatus.CANCELLED_BY_SELLER);
    }

    @Test void malformedOrdersAndFractionalDiscreteQuantitiesAreRejected() {
        assertThatThrownBy(() -> CustomerOrder.placed(SELLER,BUYER,"bad",
            List.of(line("1",MeasurementUnit.PCS,"2.00"))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerOrder.placed(SELLER,BUYER,"ZK-2345-6789",List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerOrder.placedFromBid(SELLER,BUYER,"ZK-2345-6789",
            List.of(line("1",MeasurementUnit.PCS,"2.00")),UUID.randomUUID(),
            new BigDecimal("-0.01"),NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerOrder.placed(SELLER,BUYER,"ZK-2345-6789",
            List.of(line("1.500",MeasurementUnit.PCS,"2.00"))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerOrder.placed(SELLER,BUYER,"ZK-2345-6789",
            List.of(line("1",MeasurementUnit.PCS,"0.001"))))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void buyerCancellationIsPlacedOnlyAndNeverRewritesEconomicSnapshots() {
        var order=CustomerOrder.placedFromBid(SELLER,BUYER,"ZK-2345-6789",
            List.of(line("2",MeasurementUnit.PCS,"99.99")),
            UUID.randomUUID(),new BigDecimal("11.00"),NOW);
        var originalTotal=order.getTotalAmount();
        var originalBid=order.getAcceptedBidId();
        order.cancelByBuyer();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED_BY_BUYER);
        assertThat(order.getTotalAmount()).isEqualByComparingTo(originalTotal);
        assertThat(order.getAcceptedBidId()).isEqualTo(originalBid);
        assertThatThrownBy(order::confirm).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(order::cancelBySeller).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(order::cancelByBuyer).isInstanceOf(IllegalStateException.class);

        var confirmed=CustomerOrder.placed(SELLER,BUYER,"ZK-2345-6789",
            List.of(line("1",MeasurementUnit.PCS,"100.00")));
        confirmed.confirm();
        assertThatThrownBy(confirmed::cancelByBuyer).isInstanceOf(IllegalStateException.class);
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

}
