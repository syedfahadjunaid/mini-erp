package com.minierp.purchasing;

import com.minierp.catalog.CatalogService;
import com.minierp.inventory.WarehouseService;
import com.minierp.partners.PartnerService;
import com.minierp.shared.Actor;
import com.minierp.shared.BusinessCalendar;
import com.minierp.shared.DocumentNumbers;
import com.minierp.shared.Errors;
import com.minierp.shared.Money;
import com.minierp.shared.Role;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchase order lifecycle: create and edit drafts, then submit, approve, reject, cancel, close.
 *
 * <p>Three layers of rules, each in one place:
 * <ol>
 *   <li>which move is legal from which status: {@link PurchaseOrderStatus};</li>
 *   <li>which role may make it: {@link PurchaseOrderAction#allowedRoles()} (and {@code @PreAuthorize}
 *       on the controller);</li>
 *   <li>relationship rules (you can't approve your own order): here, and again as a CHECK
 *       constraint in the database.</li>
 * </ol>
 */
@Service
@Transactional(readOnly = true)
public class PurchaseOrderService {

    private final PurchaseOrderRepository orders;
    private final PurchaseOrderHistoryRepository history;
    private final CatalogService catalog;
    private final PartnerService partners;
    private final WarehouseService warehouses;
    private final DocumentNumbers numbers;
    private final BusinessCalendar calendar;

    PurchaseOrderService(PurchaseOrderRepository orders, PurchaseOrderHistoryRepository history, CatalogService catalog,
            PartnerService partners, WarehouseService warehouses, DocumentNumbers numbers, BusinessCalendar calendar) {
        this.orders = orders;
        this.history = history;
        this.catalog = catalog;
        this.partners = partners;
        this.warehouses = warehouses;
        this.numbers = numbers;
        this.calendar = calendar;
    }

    @Transactional
    public PurchasingDtos.PurchaseOrderResponse create(PurchasingDtos.CreatePurchaseOrderRequest request, Actor actor) {
        PartnerService.SupplierInfo supplier = partners.requireActiveSupplier(request.supplierId(), "supplierId");
        warehouses.requireActiveWarehouse(request.warehouseId(), "warehouseId");
        String currency = request.currency() == null ? supplier.currency() : Money.requireCurrency(request.currency(), "currency");

        PurchaseOrder order = new PurchaseOrder(numbers.next(DocumentNumbers.Sequence.PURCHASE_ORDER), supplier.id(),
                request.warehouseId(), currency, request.expectedDate(), request.notes(), actor.username(), calendar.now());
        addLines(order, request.lines());
        orders.save(order);
        history.save(new PurchaseOrderHistory(order.getId(), "CREATE", null, order.getStatus(), actor.username(),
                calendar.now(), null));
        return toResponse(order, actor);
    }

    @Transactional
    public PurchasingDtos.PurchaseOrderResponse update(long id, PurchasingDtos.UpdatePurchaseOrderRequest request, Actor actor) {
        PurchaseOrder order = lock(id);
        if (!order.getStatus().isEditable()) {
            throw new Errors.ConflictException("not_editable", "Only DRAFT orders can be edited; this one is " + order.getStatus() + ".");
        }
        if (order.getVersion() != request.version()) {
            throw new Errors.ConflictException("stale_version",
                    "The order was changed by someone else (version " + order.getVersion() + "). Reload and try again.");
        }
        order.updateHeader(request.expectedDate(), request.notes());
        order.clearLines();
        // Hibernate runs INSERTs before DELETEs when it flushes. Flush the deletes now, or the new
        // line 1 would collide with the old line 1 on the unique (purchase_order_id, line_no).
        orders.flush();
        addLines(order, request.lines());
        orders.flush();
        return toResponse(order, actor);
    }

    @Transactional
    public PurchasingDtos.PurchaseOrderResponse act(long id, PurchaseOrderAction action, String comment, Actor actor) {
        PurchaseOrder order = lock(id);
        checkMayAct(order, action, actor);
        PurchaseOrderStatus previous = order.apply(action, actor.username(), calendar.now());
        history.save(new PurchaseOrderHistory(order.getId(), action.name(), previous, order.getStatus(), actor.username(),
                calendar.now(), comment));
        return toResponse(order, actor);
    }

    public PurchasingDtos.PurchaseOrderResponse get(long id, Actor actor) {
        return toResponse(orders.findById(id).orElseThrow(() -> new Errors.NotFoundException("Purchase order", id)), actor);
    }

    public List<PurchasingDtos.PurchaseOrderResponse> list(PurchaseOrderStatus status, Actor actor) {
        List<PurchaseOrder> found = status == null ? orders.findTop100ByOrderByIdDesc() : orders.findTop100ByStatusOrderByIdDesc(status);
        return found.stream().map(o -> toResponse(o, actor)).toList();
    }

    // ---- rules --------------------------------------------------------------------------------

    private void checkMayAct(PurchaseOrder order, PurchaseOrderAction action, Actor actor) {
        if (action.allowedRoles().stream().noneMatch(actor::has)) {
            throw new Errors.ForbiddenException("missing_role", "You need one of " + action.allowedRoles() + " to " + action + ".");
        }
        if (action.requiresDifferentPersonThanCreator() && order.getCreatedBy().equals(actor.username())) {
            throw new Errors.ForbiddenException("separation_of_duties",
                    "You created " + order.getOrderNumber() + ", so someone else has to " + action.name().toLowerCase() + " it.");
        }
        order.getStatus().after(action); // 409 if the status doesn't allow it (checked again in apply)
    }

    /** What this user may do right now. The UI shows exactly these buttons. */
    private Set<PurchaseOrderAction> allowedActions(PurchaseOrder order, Actor actor) {
        Set<PurchaseOrderAction> allowed = EnumSet.noneOf(PurchaseOrderAction.class);
        for (PurchaseOrderAction action : PurchaseOrderAction.values()) {
            boolean roleOk = action.allowedRoles().stream().anyMatch(actor::has);
            boolean personOk = !action.requiresDifferentPersonThanCreator() || !order.getCreatedBy().equals(actor.username());
            if (roleOk && personOk && order.getStatus().allows(action)) {
                allowed.add(action);
            }
        }
        return allowed;
    }

    private void addLines(PurchaseOrder order, List<PurchasingDtos.LineRequest> lines) {
        for (int i = 0; i < lines.size(); i++) {
            PurchasingDtos.LineRequest line = lines.get(i);
            String prefix = "lines[" + i + "].";
            CatalogService.ProductInfo product = catalog.requireActiveProduct(line.productId(), prefix + "productId");
            String uom = line.uom() == null ? product.baseUom() : line.uom();
            CatalogService.UnitConversion qty = catalog.convert(product.id(), uom, line.quantity(), prefix + "quantity");
            order.addLine(product.id(), uom, line.quantity(), qty.baseQuantity(), line.unitPriceMinor(),
                    Money.lineAmount(line.quantity(), line.unitPriceMinor()));
        }
    }

    PurchaseOrder lock(long id) {
        return orders.findForUpdate(id).orElseThrow(() -> new Errors.NotFoundException("Purchase order", id));
    }

    /** A plain read (no lock), for responses. */
    PurchaseOrder find(long id) {
        return orders.findById(id).orElseThrow(() -> new Errors.NotFoundException("Purchase order", id));
    }

    PurchasingDtos.PurchaseOrderResponse toResponse(PurchaseOrder o, Actor actor) {
        String ccy = o.getCurrency();
        List<PurchasingDtos.LineResponse> lines = o.getLines().stream()
                .map(l -> new PurchasingDtos.LineResponse(l.getId(), l.getLineNo(), l.getProductId(), l.getUom(),
                        l.getQuantity(), l.getBaseQuantity(), l.getReceivedBaseQuantity(),
                        Money.of(l.getUnitPriceMinor(), ccy), Money.of(l.getLineTotalMinor(), ccy)))
                .toList();
        List<PurchasingDtos.HistoryResponse> events = o.getId() == null ? List.of()
                : history.findByPurchaseOrderIdOrderByIdAsc(o.getId()).stream()
                        .map(h -> new PurchasingDtos.HistoryResponse(h.getAction(), h.getFromStatus(), h.getToStatus(),
                                h.getActor(), h.getOccurredAt(), h.getComment()))
                        .toList();
        return new PurchasingDtos.PurchaseOrderResponse(o.getId(), o.getOrderNumber(), o.getSupplierId(), o.getWarehouseId(),
                ccy, o.getStatus(), o.getExpectedDate(), o.getNotes(), o.getCreatedBy(), o.getCreatedAt(), o.getApprovedBy(),
                o.getApprovedAt(), o.getVersion(), Money.of(o.totalMinor(), ccy), lines, allowedActions(o, actor),
                o.getStatus().isEditable() && actor.has(Role.PURCHASING),
                o.getStatus().acceptsReceipts() && actor.has(Role.WAREHOUSE), events);
    }
}
