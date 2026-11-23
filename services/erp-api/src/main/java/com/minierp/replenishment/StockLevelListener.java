package com.minierp.replenishment;

import com.minierp.inventory.StockLevelChanged;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to every stock change by re-evaluating that product's low-stock alert.
 *
 * <p>Runs after the stock change committed, asynchronously and in its own transaction, so a
 * slow or failing alert check never slows down or breaks a shipment. The event is persisted
 * with the shipment and retried if this fails (at-least-once), which is fine because
 * {@link ReplenishmentService#evaluate} is idempotent.
 */
@Component
class StockLevelListener {

    private final ReplenishmentService replenishment;

    StockLevelListener(ReplenishmentService replenishment) {
        this.replenishment = replenishment;
    }

    @ApplicationModuleListener
    public void on(StockLevelChanged event) {
        replenishment.evaluate(event.warehouseId(), event.productId());
    }
}
