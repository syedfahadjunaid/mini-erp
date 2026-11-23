package com.minierp.sales;

import com.minierp.catalog.CatalogService;
import com.minierp.inventory.StockLedger;
import com.minierp.inventory.StockMovement;
import com.minierp.inventory.StockQueries;
import com.minierp.inventory.WarehouseService;
import com.minierp.partners.PartnerService;
import com.minierp.shared.Actor;
import com.minierp.shared.BusinessCalendar;
import com.minierp.shared.DocumentNumbers;
import com.minierp.shared.Errors;
import com.minierp.shared.InsufficientStockException;
import com.minierp.shared.Money;
import com.minierp.shared.Role;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales order lifecycle, and the stock reservations that go with it.
 *
 * <p>Reservation is all-or-nothing: confirming an order reserves every line in one transaction.
 * The availability check runs twice:
 * <ol>
 *   <li>a plain read first, to answer with every short line at once ("A: 3 available, 5 needed;
 *       B: 0 available, 2 needed");</li>
 *   <li>then the real reservation, where the ledger trigger locks the balance row and checks
 *       again. If another order took the stock in between, this second check fails, the whole
 *       confirmation rolls back, and the client gets the same 422. Two orders can never both
 *       take the last unit: the row lock makes them queue, and the second sees what the first
 *       left (ReservationConcurrencyTests proves it).</li>
 * </ol>
 */
@Service
@Transactional(readOnly = true)
public class SalesOrderService {

    private final SalesOrderRepository orders;
    private final SalesOrderHistoryRepository history;
    private final CatalogService catalog;
    private final PartnerService partners;
    private final WarehouseService warehouses;
    private final StockQueries stock;
    private final StockLedger ledger;
    private final DocumentNumbers numbers;
    private final BusinessCalendar calendar;

    SalesOrderService(SalesOrderRepository orders, SalesOrderHistoryRepository history, CatalogService catalog,
            PartnerService partners, WarehouseService warehouses, StockQueries stock, StockLedger ledger,
            DocumentNumbers numbers, BusinessCalendar calendar) {
        this.orders = orders;
        this.history = history;
        this.catalog = catalog;
        this.partners = partners;
        this.warehouses = warehouses;
        this.stock = stock;
        this.ledger = ledger;
        this.numbers = numbers;
        this.calendar = calendar;
    }

    @Transactional
    public SalesDtos.SalesOrderResponse create(SalesDtos.CreateSalesOrderRequest request, Actor actor) {
        PartnerService.CustomerInfo customer = partners.requireActiveCustomer(request.customerId(), "customerId");
        warehouses.requireActiveWarehouse(request.warehouseId(), "warehouseId");
        SalesOrder order = new SalesOrder(numbers.next(DocumentNumbers.Sequence.SALES_ORDER), customer.id(),
                request.warehouseId(), customer.currency(), request.notes(), actor.username(), calendar.now());
        addLines(order, request.lines());
        orders.save(order);
        history.save(new SalesOrderHistory(order.getId(), "CREATE", null, order.getStatus(), actor.username(),
                calendar.now(), null));
        return toResponse(order, actor);
    }

    @Transactional
    public SalesDtos.SalesOrderResponse update(long id, SalesDtos.UpdateSalesOrderRequest request, Actor actor) {
        SalesOrder order = lock(id);
        if (!order.getStatus().isEditable()) {
            throw new Errors.ConflictException("not_editable", "Only DRAFT orders can be edited; this one is " + order.getStatus() + ".");
        }
        if (order.getVersion() != request.version()) {
            throw new Errors.ConflictException("stale_version",
                    "The order was changed by someone else (version " + order.getVersion() + "). Reload and try again.");
        }
        order.updateNotes(request.notes());
        order.clearLines();
        orders.flush(); // deletes before inserts (see PurchaseOrderService.update)
        addLines(order, request.lines());
        orders.flush();
        return toResponse(order, actor);
    }

    @Transactional
    public SalesDtos.SalesOrderResponse act(long id, SalesOrderAction action, String comment, Actor actor) {
        if (!actor.has(Role.SALES)) {
            throw new Errors.ForbiddenException("missing_role", "You need SALES to " + action + " a sales order.");
        }
        SalesOrder order = lock(id);
        SalesOrderStatus previous = order.getStatus();
        previous.after(action); // 409 before any stock is touched if the status doesn't allow it

        switch (action) {
            case CONFIRM -> reserveAll(order, actor);
            case CANCEL, CLOSE -> releaseOpen(order, actor);
        }
        order.apply(action, actor.username(), calendar.now());
        history.save(new SalesOrderHistory(order.getId(), action.name(), previous, order.getStatus(), actor.username(),
                calendar.now(), comment));
        return toResponse(order, actor);
    }

    public SalesDtos.SalesOrderResponse get(long id, Actor actor) {
        return toResponse(find(id), actor);
    }

    public List<SalesDtos.SalesOrderResponse> list(SalesOrderStatus status, Actor actor) {
        List<SalesOrder> found = status == null ? orders.findTop100ByOrderByIdDesc() : orders.findTop100ByStatusOrderByIdDesc(status);
        return found.stream().map(o -> toResponse(o, actor)).toList();
    }

    // ---- reservations -------------------------------------------------------------------------

    private void reserveAll(SalesOrder order, Actor actor) {
        // 1. Friendly pre-check: total needed per product (two lines may share a product).
        Map<Long, Long> neededPerProduct = new TreeMap<>();
        for (SalesOrderLine line : order.getLines()) {
            neededPerProduct.merge(line.getProductId(), line.getBaseQuantity(), Math::addExact);
        }
        List<InsufficientStockException.Shortage> shortages = new ArrayList<>();
        neededPerProduct.forEach((productId, needed) -> {
            long available = stock.level(order.getWarehouseId(), productId).available();
            if (available < needed) {
                shortages.add(new InsufficientStockException.Shortage(order.getWarehouseId(), null, productId, available, needed));
            }
        });
        if (!shortages.isEmpty()) {
            throw new InsufficientStockException("insufficient_stock",
                    "Not enough available stock to confirm " + order.getOrderNumber() + ".", shortages);
        }
        // 2. The real thing: the ledger trigger locks and re-checks each balance.
        List<StockMovement> reservations = order.getLines().stream()
                .map(line -> StockMovement.reserve(order.getWarehouseId(), line.getProductId(), line.getBaseQuantity(), line.getId()))
                .toList();
        ledger.post(reservations, actor);
    }

    /** Cancel / close: give back whatever is still reserved for the order's lines. */
    private void releaseOpen(SalesOrder order, Actor actor) {
        if (!order.getStatus().holdsReservations()) {
            return; // a DRAFT never reserved anything
        }
        List<StockMovement> releases = order.getLines().stream()
                .filter(line -> line.openBaseQuantity() > 0)
                .map(line -> StockMovement.release(order.getWarehouseId(), line.getProductId(), line.openBaseQuantity(), line.getId()))
                .toList();
        if (!releases.isEmpty()) {
            ledger.post(releases, actor);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void addLines(SalesOrder order, List<SalesDtos.LineRequest> lines) {
        for (int i = 0; i < lines.size(); i++) {
            SalesDtos.LineRequest line = lines.get(i);
            String prefix = "lines[" + i + "].";
            CatalogService.ProductInfo product = catalog.requireActiveProduct(line.productId(), prefix + "productId");
            String uom = line.uom() == null ? product.baseUom() : line.uom();
            CatalogService.UnitConversion qty = catalog.convert(product.id(), uom, line.quantity(), prefix + "quantity");
            order.addLine(product.id(), uom, line.quantity(), qty.baseQuantity(), line.unitPriceMinor(), product.taxRateBp(),
                    Money.lineAmount(line.quantity(), line.unitPriceMinor()));
        }
    }

    SalesOrder lock(long id) {
        return orders.findForUpdate(id).orElseThrow(() -> new Errors.NotFoundException("Sales order", id));
    }

    SalesOrder find(long id) {
        return orders.findById(id).orElseThrow(() -> new Errors.NotFoundException("Sales order", id));
    }

    private Set<SalesOrderAction> allowedActions(SalesOrder order, Actor actor) {
        Set<SalesOrderAction> allowed = EnumSet.noneOf(SalesOrderAction.class);
        if (actor.has(Role.SALES)) {
            for (SalesOrderAction action : SalesOrderAction.values()) {
                if (order.getStatus().allows(action)) {
                    allowed.add(action);
                }
            }
        }
        return allowed;
    }

    SalesDtos.SalesOrderResponse toResponse(SalesOrder o, Actor actor) {
        String ccy = o.getCurrency();
        List<SalesDtos.LineResponse> lines = o.getLines().stream()
                .map(l -> new SalesDtos.LineResponse(l.getId(), l.getLineNo(), l.getProductId(), l.getUom(), l.getQuantity(),
                        l.getBaseQuantity(), l.getShippedBaseQuantity(), l.openBaseQuantity(),
                        Money.of(l.getUnitPriceMinor(), ccy), l.getTaxRateBp(), Money.of(l.getLineNetMinor(), ccy)))
                .toList();
        List<SalesDtos.HistoryResponse> events = history.findBySalesOrderIdOrderByIdAsc(o.getId()).stream()
                .map(h -> new SalesDtos.HistoryResponse(h.getAction(), h.getFromStatus(), h.getToStatus(), h.getActor(),
                        h.getOccurredAt(), h.getComment()))
                .toList();
        return new SalesDtos.SalesOrderResponse(o.getId(), o.getOrderNumber(), o.getCustomerId(), o.getWarehouseId(), ccy,
                o.getStatus(), o.getNotes(), o.getCreatedBy(), o.getCreatedAt(), o.getConfirmedBy(), o.getConfirmedAt(),
                o.getVersion(), Money.of(o.netTotalMinor(), ccy), lines, allowedActions(o, actor),
                o.getStatus().isEditable() && actor.has(Role.SALES),
                o.getStatus().acceptsShipments() && actor.has(Role.WAREHOUSE), events);
    }
}
