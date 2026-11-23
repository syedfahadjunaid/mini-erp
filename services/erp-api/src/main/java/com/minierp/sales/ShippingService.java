package com.minierp.sales;

import com.minierp.catalog.CatalogService;
import com.minierp.inventory.StockLedger;
import com.minierp.inventory.StockMovement;
import com.minierp.inventory.StockQueries;
import com.minierp.shared.Actor;
import com.minierp.shared.BusinessCalendar;
import com.minierp.shared.DocumentNumbers;
import com.minierp.shared.Errors;
import com.minierp.shared.InsufficientStockException;
import com.minierp.shared.Quantities;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shipping a confirmed sales order (all of it, or part of it).
 *
 * <p>One transaction: the shipment document, the shipped quantities on the order lines, the
 * order's new status, a SHIPMENT movement per line and bin (which consumes the reservation),
 * and a {@link ShipmentDispatched} event. The event is stored with the transaction (Spring
 * Modulith's registry) and handed to the invoicing module after commit.
 *
 * <p>Bins are picked automatically, in bin-code order. The plan is made from a plain read; if
 * another shipment empties a bin in the meantime, the ledger trigger refuses the movement and
 * the client gets a 409 asking to retry.
 */
@Service
@Transactional(readOnly = true)
public class ShippingService {

    private final SalesOrderService orders;
    private final ShipmentRepository shipments;
    private final SalesOrderHistoryRepository history;
    private final CatalogService catalog;
    private final StockQueries stock;
    private final StockLedger ledger;
    private final DocumentNumbers numbers;
    private final BusinessCalendar calendar;
    private final ApplicationEventPublisher events;

    ShippingService(SalesOrderService orders, ShipmentRepository shipments, SalesOrderHistoryRepository history,
            CatalogService catalog, StockQueries stock, StockLedger ledger, DocumentNumbers numbers, BusinessCalendar calendar,
            ApplicationEventPublisher events) {
        this.orders = orders;
        this.shipments = shipments;
        this.history = history;
        this.catalog = catalog;
        this.stock = stock;
        this.ledger = ledger;
        this.numbers = numbers;
        this.calendar = calendar;
        this.events = events;
    }

    @Transactional
    public SalesDtos.ShipmentResponse ship(long salesOrderId, SalesDtos.ShipmentRequest request, Actor actor) {
        SalesOrder order = orders.lock(salesOrderId);
        if (!order.getStatus().acceptsShipments()) {
            throw new Errors.InvalidTransitionException("sales order", order.getStatus(), "ship");
        }

        Shipment shipment = new Shipment(numbers.next(DocumentNumbers.Sequence.SHIPMENT), order.getId(), order.getWarehouseId(),
                actor.username(), calendar.now());
        List<SalesOrderLine> orderLines = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < request.lines().size(); i++) {
            SalesDtos.ShipmentLineRequest line = request.lines().get(i);
            String prefix = "lines[" + i + "].";
            SalesOrderLine orderLine = order.getLines().stream()
                    .filter(l -> l.getId().equals(line.lineId()))
                    .findFirst()
                    .orElseThrow(() -> Errors.BusinessRuleException.forField("unknown_line", prefix + "lineId",
                            "Line " + line.lineId() + " is not on " + order.getOrderNumber() + "."));
            if (!seen.add(orderLine.getId())) {
                throw Errors.BusinessRuleException.forField("duplicate_line", prefix + "lineId",
                        "Line " + orderLine.getLineNo() + " appears twice in this shipment.");
            }
            long factor = catalog.baseUnitsPerUom(orderLine.getProductId(), orderLine.getUom(), prefix + "quantity");
            long base = Quantities.toBase(line.quantity(), factor, prefix + "quantity");
            if (base > orderLine.openBaseQuantity()) {
                throw Errors.BusinessRuleException.forField("over_shipment", prefix + "quantity",
                        "Line " + orderLine.getLineNo() + " has only " + orderLine.openBaseQuantity()
                                + " base units left to ship; " + base + " were given.");
            }
            shipment.addLine(orderLine, line.quantity(), base);
            orderLines.add(orderLine);
        }
        shipments.save(shipment); // inserts header and lines (IDENTITY), so they have ids

        Map<Long, List<SalesDtos.Pick>> picks = planPicks(order.getWarehouseId(), shipment.getLines());
        List<StockMovement> movements = new ArrayList<>();
        for (ShipmentLine line : shipment.getLines()) {
            for (SalesDtos.Pick pick : picks.get(line.getId())) {
                movements.add(StockMovement.shipment(order.getWarehouseId(), pick.binId(), line.getProductId(), pick.quantity(),
                        line.getId()));
            }
        }
        for (int i = 0; i < orderLines.size(); i++) {
            orderLines.get(i).ship(shipment.getLines().get(i).getBaseQuantity());
        }
        SalesOrderStatus previous = order.afterShipment();
        history.save(new SalesOrderHistory(order.getId(), "SHIP", previous, order.getStatus(), actor.username(),
                calendar.now(), shipment.getShipmentNumber()));

        try {
            ledger.post(movements, actor);
        } catch (InsufficientStockException e) {
            // The plan said the bins had it; someone else took it while we planned. Safe to retry.
            throw new Errors.ConflictException("pick_conflict",
                    "Stock in a bin changed while the shipment was being picked. Try again. (" + e.getMessage() + ")");
        }
        events.publishEvent(new ShipmentDispatched(shipment.getId(), order.getId(), shipment.getShipmentNumber()));
        return toResponse(shipment, picks);
    }

    public SalesDtos.ShipmentResponse get(long id) {
        Shipment shipment = shipments.findById(id).orElseThrow(() -> new Errors.NotFoundException("Shipment", id));
        return toResponse(shipment, Map.of());
    }

    public List<SalesDtos.ShipmentResponse> forOrder(long salesOrderId) {
        orders.find(salesOrderId);
        return shipments.findBySalesOrderIdOrderByIdAsc(salesOrderId).stream().map(s -> toResponse(s, Map.of())).toList();
    }

    /**
     * Greedy picking: for each line, take from the bins holding the product, in bin-code order,
     * until the quantity is covered. Lines of the same product share the bins' stock.
     */
    private Map<Long, List<SalesDtos.Pick>> planPicks(long warehouseId, List<ShipmentLine> lines) {
        Map<Long, Map<Long, Long>> leftPerProduct = new HashMap<>(); // product -> (bin -> quantity left)
        Map<Long, List<SalesDtos.Pick>> picks = new LinkedHashMap<>();
        for (ShipmentLine line : lines) {
            Map<Long, Long> bins = leftPerProduct.computeIfAbsent(line.getProductId(), product -> {
                Map<Long, Long> inBins = new LinkedHashMap<>();
                stock.binsWithStock(warehouseId, product).forEach(b -> inBins.put(b.binId(), b.onHand()));
                return inBins;
            });
            long needed = line.getBaseQuantity();
            List<SalesDtos.Pick> linePicks = new ArrayList<>();
            for (Map.Entry<Long, Long> bin : bins.entrySet()) {
                if (needed == 0) {
                    break;
                }
                long take = Math.min(needed, bin.getValue());
                if (take > 0) {
                    linePicks.add(new SalesDtos.Pick(bin.getKey(), take));
                    bin.setValue(bin.getValue() - take);
                    needed -= take;
                }
            }
            if (needed > 0) {
                // Reserved stock is always on hand somewhere in the warehouse, so this means a
                // concurrent movement changed the bins after we read them.
                throw new Errors.ConflictException("pick_conflict",
                        "Not enough stock in the bins of warehouse " + warehouseId + " for product " + line.getProductId()
                                + " right now. Try again.");
            }
            picks.put(line.getId(), linePicks);
        }
        return picks;
    }

    private static SalesDtos.ShipmentResponse toResponse(Shipment s, Map<Long, List<SalesDtos.Pick>> picks) {
        List<SalesDtos.ShipmentLineResponse> lines = s.getLines().stream()
                .map(l -> new SalesDtos.ShipmentLineResponse(l.getId(), l.getSalesOrderLineId(), l.getProductId(),
                        l.getQuantity(), l.getBaseQuantity(), picks.getOrDefault(l.getId(), List.of())))
                .toList();
        return new SalesDtos.ShipmentResponse(s.getId(), s.getShipmentNumber(), s.getSalesOrderId(), s.getWarehouseId(),
                s.getShippedBy(), s.getShippedAt(), lines);
    }
}
