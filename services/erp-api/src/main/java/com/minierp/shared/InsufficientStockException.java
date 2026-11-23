package com.minierp.shared;

import java.util.List;

/**
 * Not enough stock for a reservation, shipment, transfer or adjustment: 422 with the numbers,
 * so a client can show "only 3 available".
 *
 * @see Shortage
 */
public class InsufficientStockException extends Errors.BusinessRuleException {

    /**
     * One product that's short.
     *
     * @param warehouseId where
     * @param binId       the bin, when a bin was short; {@code null} when the warehouse as a whole was
     * @param productId   what
     * @param available   how much there was (available in the warehouse, or on hand in the bin)
     * @param requested   how much was needed, in base units
     */
    public record Shortage(long warehouseId, Long binId, long productId, long available, long requested) {}

    private final List<Shortage> shortages;

    public InsufficientStockException(String code, String message, List<Shortage> shortages) {
        super(code, message);
        this.shortages = List.copyOf(shortages);
    }

    public List<Shortage> shortages() {
        return shortages;
    }
}
