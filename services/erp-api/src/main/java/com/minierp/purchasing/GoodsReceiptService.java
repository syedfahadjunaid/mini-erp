package com.minierp.purchasing;

import com.minierp.catalog.CatalogService;
import com.minierp.inventory.StockLedger;
import com.minierp.inventory.StockMovement;
import com.minierp.inventory.WarehouseService;
import com.minierp.shared.Actor;
import com.minierp.shared.BusinessCalendar;
import com.minierp.shared.DocumentNumbers;
import com.minierp.shared.Errors;
import com.minierp.shared.Quantities;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receiving goods against an approved purchase order. One transaction does all of it: the
 * receipt document, the received quantities on the order lines, the order's new status, and a
 * RECEIPT movement per line in the stock ledger. Either everything is booked or nothing is.
 */
@Service
@Transactional(readOnly = true)
public class GoodsReceiptService {

    private final PurchaseOrderService orders;
    private final GoodsReceiptRepository receipts;
    private final PurchaseOrderHistoryRepository history;
    private final CatalogService catalog;
    private final WarehouseService warehouses;
    private final StockLedger ledger;
    private final DocumentNumbers numbers;
    private final BusinessCalendar calendar;

    GoodsReceiptService(PurchaseOrderService orders, GoodsReceiptRepository receipts, PurchaseOrderHistoryRepository history,
            CatalogService catalog, WarehouseService warehouses, StockLedger ledger, DocumentNumbers numbers,
            BusinessCalendar calendar) {
        this.orders = orders;
        this.receipts = receipts;
        this.history = history;
        this.catalog = catalog;
        this.warehouses = warehouses;
        this.ledger = ledger;
        this.numbers = numbers;
        this.calendar = calendar;
    }

    @Transactional
    public PurchasingDtos.GoodsReceiptResponse receive(long purchaseOrderId, PurchasingDtos.ReceiptRequest request, Actor actor) {
        // Locked: two receipts against the same order are booked one after the other, so they
        // can't both fit into the same remaining quantity.
        PurchaseOrder order = orders.lock(purchaseOrderId);
        if (!order.getStatus().acceptsReceipts()) {
            throw new Errors.InvalidTransitionException("purchase order", order.getStatus(), "receive goods");
        }

        GoodsReceipt receipt = new GoodsReceipt(numbers.next(DocumentNumbers.Sequence.GOODS_RECEIPT), order.getId(),
                order.getWarehouseId(), actor.username(), calendar.now());
        List<PurchaseOrderLine> receivedLines = new ArrayList<>();
        List<Long> baseQuantities = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < request.lines().size(); i++) {
            PurchasingDtos.ReceiptLineRequest line = request.lines().get(i);
            String prefix = "lines[" + i + "].";
            PurchaseOrderLine orderLine = order.getLines().stream()
                    .filter(l -> l.getId().equals(line.lineId()))
                    .findFirst()
                    .orElseThrow(() -> Errors.BusinessRuleException.forField("unknown_line", prefix + "lineId",
                            "Line " + line.lineId() + " is not on " + order.getOrderNumber() + "."));
            if (!seen.add(orderLine.getId())) {
                throw Errors.BusinessRuleException.forField("duplicate_line", prefix + "lineId",
                        "Line " + orderLine.getLineNo() + " appears twice in this receipt.");
            }
            long factor = catalog.baseUnitsPerUom(orderLine.getProductId(), orderLine.getUom(), prefix + "quantity");
            long base = Quantities.toBase(line.quantity(), factor, prefix + "quantity");
            if (base > orderLine.remainingBaseQuantity()) {
                throw Errors.BusinessRuleException.forField("over_receipt", prefix + "quantity",
                        "Line " + orderLine.getLineNo() + " has only " + orderLine.remainingBaseQuantity()
                                + " base units left to receive; " + base + " were given.");
            }
            long binId = warehouses.binOrReceiving(order.getWarehouseId(), line.binId(), prefix + "binId").id();
            receipt.addLine(orderLine, binId, line.quantity(), base);
            receivedLines.add(orderLine);
            baseQuantities.add(base);
        }
        receipts.save(receipt); // header and lines inserted now (IDENTITY), so the lines have ids

        for (int i = 0; i < receivedLines.size(); i++) {
            receivedLines.get(i).receive(baseQuantities.get(i));
        }
        PurchaseOrderStatus previous = order.afterReceipt();
        history.save(new PurchaseOrderHistory(order.getId(), "RECEIVE", previous, order.getStatus(), actor.username(),
                calendar.now(), receipt.getReceiptNumber()));

        List<StockMovement> movements = new ArrayList<>();
        for (GoodsReceiptLine line : receipt.getLines()) {
            movements.add(StockMovement.receipt(line.getWarehouseId(), line.getBinId(), line.getProductId(),
                    line.getBaseQuantity(), line.getId()));
        }
        ledger.post(movements, actor);
        return toResponse(receipt, order.getStatus());
    }

    public PurchasingDtos.GoodsReceiptResponse get(long id) {
        GoodsReceipt receipt = receipts.findById(id).orElseThrow(() -> new Errors.NotFoundException("Goods receipt", id));
        return toResponse(receipt, orders.find(receipt.getPurchaseOrderId()).getStatus());
    }

    public List<PurchasingDtos.GoodsReceiptResponse> forOrder(long purchaseOrderId) {
        PurchaseOrderStatus status = orders.find(purchaseOrderId).getStatus();
        return receipts.findByPurchaseOrderIdOrderByIdAsc(purchaseOrderId).stream().map(r -> toResponse(r, status)).toList();
    }

    private static PurchasingDtos.GoodsReceiptResponse toResponse(GoodsReceipt r, PurchaseOrderStatus orderStatus) {
        List<PurchasingDtos.ReceiptLineResponse> lines = r.getLines().stream()
                .map(l -> new PurchasingDtos.ReceiptLineResponse(l.getId(), l.getPurchaseOrderLineId(), l.getProductId(),
                        l.getBinId(), l.getQuantity(), l.getBaseQuantity()))
                .toList();
        return new PurchasingDtos.GoodsReceiptResponse(r.getId(), r.getReceiptNumber(), r.getPurchaseOrderId(),
                r.getWarehouseId(), r.getReceivedBy(), r.getReceivedAt(), lines, orderStatus);
    }
}
