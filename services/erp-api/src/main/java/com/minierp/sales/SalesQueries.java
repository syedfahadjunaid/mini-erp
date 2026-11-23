package com.minierp.sales;

import com.minierp.shared.Errors;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sales module's public read API for other modules. Invoicing uses it to bill a shipment
 * without touching sales entities or tables itself.
 */
@Service
@Transactional(readOnly = true)
public class SalesQueries {

    /** Everything needed to invoice one shipment. */
    public record ShipmentForInvoice(long shipmentId, String shipmentNumber, long salesOrderId, String orderNumber,
            long customerId, String currency, Instant shippedAt, List<Line> lines) {

        /** A shipped line with the order line's price and tax rate. Quantity in {@code uom}. */
        public record Line(long shipmentLineId, long productId, String uom, BigDecimal quantity, long unitPriceMinor,
                int taxRateBp) {}
    }

    private final ShipmentRepository shipments;
    private final SalesOrderRepository orders;

    SalesQueries(ShipmentRepository shipments, SalesOrderRepository orders) {
        this.shipments = shipments;
        this.orders = orders;
    }

    public ShipmentForInvoice shipmentForInvoice(long shipmentId) {
        Shipment shipment = shipments.findById(shipmentId).orElseThrow(() -> new Errors.NotFoundException("Shipment", shipmentId));
        SalesOrder order = orders.findById(shipment.getSalesOrderId())
                .orElseThrow(() -> new IllegalStateException("Shipment " + shipmentId + " without its order"));
        List<ShipmentForInvoice.Line> lines = shipment.getLines().stream()
                .map(line -> {
                    SalesOrderLine orderLine = order.getLines().stream()
                            .filter(l -> l.getId() == line.getSalesOrderLineId())
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("Shipment line without its order line"));
                    return new ShipmentForInvoice.Line(line.getId(), line.getProductId(), orderLine.getUom(), line.getQuantity(),
                            orderLine.getUnitPriceMinor(), orderLine.getTaxRateBp());
                })
                .toList();
        return new ShipmentForInvoice(shipment.getId(), shipment.getShipmentNumber(), order.getId(), order.getOrderNumber(),
                order.getCustomerId(), order.getCurrency(), shipment.getShippedAt(), lines);
    }
}
