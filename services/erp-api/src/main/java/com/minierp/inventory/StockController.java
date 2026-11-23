package com.minierp.inventory;

import com.minierp.shared.Actor;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stock balances, the movement ledger, adjustments and transfers. Everyone signed in can read
 * stock; adjustments need MANAGER, transfers need WAREHOUSE.
 */
@RestController
@RequestMapping("/api")
class StockController {

    private final StockQueries queries;
    private final StockAdjustmentService adjustments;
    private final TransferService transfers;

    StockController(StockQueries queries, StockAdjustmentService adjustments, TransferService transfers) {
        this.queries = queries;
        this.adjustments = adjustments;
        this.transfers = transfers;
    }

    @GetMapping("/stock/levels")
    List<StockDtos.StockLevelView> levels(@RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long productId) {
        return queries.levels(warehouseId, productId);
    }

    @GetMapping("/stock/bins")
    List<StockDtos.BinStockView> bins(@RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long productId) {
        return queries.bins(warehouseId, productId);
    }

    /** The ledger, newest first. {@code from} inclusive, {@code to} exclusive (ISO-8601 instants). */
    @GetMapping("/stock/movements")
    List<StockDtos.MovementView> movements(@RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "100") int limit) {
        return queries.movements(warehouseId, productId, from, to, limit);
    }

    @PostMapping("/stock/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MANAGER')")
    StockDtos.AdjustmentResponse adjust(@Valid @RequestBody StockDtos.AdjustmentRequest request, Actor actor) {
        return adjustments.adjust(request, actor);
    }

    @GetMapping("/transfers")
    List<TransferDtos.TransferResponse> transfers() {
        return transfers.recent();
    }

    @GetMapping("/transfers/{id}")
    TransferDtos.TransferResponse transfer(@PathVariable long id) {
        return transfers.get(id);
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('WAREHOUSE')")
    TransferDtos.TransferResponse createTransfer(@Valid @RequestBody TransferDtos.CreateTransferRequest request, Actor actor) {
        return transfers.create(request, actor);
    }

    @PostMapping("/transfers/{id}/receive")
    @PreAuthorize("hasRole('WAREHOUSE')")
    TransferDtos.TransferResponse receiveTransfer(@PathVariable long id,
            @RequestBody(required = false) TransferDtos.ReceiveTransferRequest request, Actor actor) {
        return transfers.receive(id, request, actor);
    }
}
