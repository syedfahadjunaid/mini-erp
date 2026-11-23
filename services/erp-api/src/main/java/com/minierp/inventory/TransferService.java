package com.minierp.inventory;

import com.minierp.catalog.CatalogService;
import com.minierp.shared.Actor;
import com.minierp.shared.BusinessCalendar;
import com.minierp.shared.DocumentNumbers;
import com.minierp.shared.Errors;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inter-warehouse transfers in two steps: dispatch (stock leaves the source bins, the transfer is
 * IN_TRANSIT) and receive (stock enters a destination bin). Only AVAILABLE stock can be
 * transferred: units reserved for a customer stay where the customer's order will ship from.
 */
@Service
@Transactional(readOnly = true)
public class TransferService {

    private final StockTransferRepository transfers;
    private final StockLedger ledger;
    private final WarehouseService warehouses;
    private final CatalogService catalog;
    private final DocumentNumbers numbers;
    private final BusinessCalendar calendar;

    TransferService(StockTransferRepository transfers, StockLedger ledger, WarehouseService warehouses,
            CatalogService catalog, DocumentNumbers numbers, BusinessCalendar calendar) {
        this.transfers = transfers;
        this.ledger = ledger;
        this.warehouses = warehouses;
        this.catalog = catalog;
        this.numbers = numbers;
        this.calendar = calendar;
    }

    @Transactional
    public TransferDtos.TransferResponse create(TransferDtos.CreateTransferRequest request, Actor actor) {
        long from = warehouses.requireActiveWarehouse(request.fromWarehouseId(), "fromWarehouseId").id();
        long to = warehouses.requireActiveWarehouse(request.toWarehouseId(), "toWarehouseId").id();
        if (from == to) {
            throw Errors.BusinessRuleException.forField("same_warehouse", "toWarehouseId",
                    "A transfer goes between two different warehouses.");
        }

        StockTransfer transfer = new StockTransfer(numbers.next(DocumentNumbers.Sequence.STOCK_TRANSFER), from, to,
                actor.username(), calendar.now());
        for (int i = 0; i < request.lines().size(); i++) {
            TransferDtos.LineRequest line = request.lines().get(i);
            String prefix = "lines[" + i + "].";
            CatalogService.ProductInfo product = catalog.requireActiveProduct(line.productId(), prefix + "productId");
            warehouses.requireBin(from, line.fromBinId(), prefix + "fromBinId");
            String uom = line.uom() == null ? product.baseUom() : line.uom();
            long quantity = catalog.convert(product.id(), uom, line.quantity(), prefix + "quantity").baseQuantity();
            transfer.addLine(product.id(), line.fromBinId(), quantity);
        }
        transfers.save(transfer); // IDENTITY: header and lines are inserted now, so the lines have ids

        List<StockMovement> out = new ArrayList<>();
        for (StockTransferLine line : transfer.getLines()) {
            out.add(StockMovement.transferOut(from, line.getFromBinId(), line.getProductId(), line.getQuantity(), line.getId()));
        }
        ledger.post(out, actor);
        return toResponse(transfer);
    }

    @Transactional
    public TransferDtos.TransferResponse receive(long transferId, TransferDtos.ReceiveTransferRequest request, Actor actor) {
        StockTransfer transfer = transfers.findForUpdate(transferId)
                .orElseThrow(() -> new Errors.NotFoundException("Transfer", transferId));
        Long requestedBin = request == null ? null : request.toBinId();
        long toBin = warehouses.binOrReceiving(transfer.getToWarehouseId(), requestedBin, "toBinId").id();

        transfer.receive(toBin, actor.username(), calendar.now()); // 409 if already received

        List<StockMovement> in = new ArrayList<>();
        for (StockTransferLine line : transfer.getLines()) {
            in.add(StockMovement.transferIn(transfer.getToWarehouseId(), toBin, line.getProductId(), line.getQuantity(),
                    line.getId()));
        }
        ledger.post(in, actor); // flushes first, so the lines' to_bin_id is written before the movements reference it
        return toResponse(transfer);
    }

    public TransferDtos.TransferResponse get(long id) {
        return toResponse(transfers.findById(id).orElseThrow(() -> new Errors.NotFoundException("Transfer", id)));
    }

    public List<TransferDtos.TransferResponse> recent() {
        return transfers.findTop100ByOrderByIdDesc().stream().map(TransferService::toResponse).toList();
    }

    private static TransferDtos.TransferResponse toResponse(StockTransfer t) {
        List<TransferDtos.LineResponse> lines = t.getLines().stream()
                .map(l -> new TransferDtos.LineResponse(l.getId(), l.getLineNo(), l.getProductId(), l.getQuantity(),
                        l.getFromBinId(), l.getToBinId()))
                .toList();
        return new TransferDtos.TransferResponse(t.getId(), t.getTransferNumber(), t.getFromWarehouseId(), t.getToWarehouseId(),
                t.getStatus(), t.getCreatedBy(), t.getCreatedAt(), t.getReceivedBy(), t.getReceivedAt(), lines);
    }
}
