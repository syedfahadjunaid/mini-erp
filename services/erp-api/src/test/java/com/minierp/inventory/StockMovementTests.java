package com.minierp.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The factories choose the signs; the record refuses shapes the ledger doesn't allow. */
class StockMovementTests {

    @Test
    void factoriesProduceTheDocumentedShapes() {
        StockMovement receipt = StockMovement.receipt(1, 2, 3, 10, 9);
        assertThat(receipt.onHandDelta()).isEqualTo(10);
        assertThat(receipt.reservedDelta()).isZero();

        StockMovement reserve = StockMovement.reserve(1, 3, 10, 9);
        assertThat(reserve.reservedDelta()).isEqualTo(10);
        assertThat(reserve.binId()).isNull();

        assertThat(StockMovement.release(1, 3, 10, 9).reservedDelta()).isEqualTo(-10);

        StockMovement shipment = StockMovement.shipment(1, 2, 3, 10, 9);
        assertThat(shipment.onHandDelta()).isEqualTo(-10);
        assertThat(shipment.reservedDelta()).isEqualTo(-10); // shipping consumes the reservation

        assertThat(StockMovement.transferOut(1, 2, 3, 10, 9).onHandDelta()).isEqualTo(-10);
        assertThat(StockMovement.adjustment(1, 2, 3, -4, 9).onHandDelta()).isEqualTo(-4);
    }

    @Test
    void refusesImpossibleMovements() {
        assertThatThrownBy(() -> StockMovement.receipt(1, 2, 3, -10, 9)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StockMovement.reserve(1, 3, 0, 9)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StockMovement.adjustment(1, 2, 3, 0, 9)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StockMovement(MovementType.RESERVE, 1, 2L, 3, 0, 5, 9))
                .hasMessageContaining("must not have a bin");
        assertThatThrownBy(() -> new StockMovement(MovementType.RECEIPT, 1, null, 3, 5, 0, 9))
                .hasMessageContaining("needs a bin");
    }
}
