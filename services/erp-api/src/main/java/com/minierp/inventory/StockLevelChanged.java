package com.minierp.inventory;

import java.time.Instant;

/**
 * Domain event: the stock of a product in a warehouse changed (published once per warehouse and
 * product per ledger posting, with the balances right after it).
 *
 * <p>Listeners run after the change has committed, possibly much later (a retried event) and
 * possibly out of order, so they should treat these numbers as a hint and re-read the current
 * balance before deciding anything. The replenishment module does exactly that.
 */
public record StockLevelChanged(long warehouseId, long productId, long onHand, long reserved, long available,
        Instant occurredAt) {}
