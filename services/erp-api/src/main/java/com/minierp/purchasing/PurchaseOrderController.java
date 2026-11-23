package com.minierp.purchasing;

import com.minierp.shared.Actor;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Purchase orders and goods receipts. Thin on purpose: validation of the body (Bean Validation),
 * the role check ({@code @PreAuthorize}), and a call into a service that does the rest.
 */
@RestController
@RequestMapping("/api")
class PurchaseOrderController {

    private final PurchaseOrderService orders;
    private final GoodsReceiptService receipts;

    PurchaseOrderController(PurchaseOrderService orders, GoodsReceiptService receipts) {
        this.orders = orders;
        this.receipts = receipts;
    }

    @GetMapping("/purchase-orders")
    List<PurchasingDtos.PurchaseOrderResponse> list(@RequestParam(required = false) PurchaseOrderStatus status, Actor actor) {
        return orders.list(status, actor);
    }

    @GetMapping("/purchase-orders/{id}")
    PurchasingDtos.PurchaseOrderResponse get(@PathVariable long id, Actor actor) {
        return orders.get(id, actor);
    }

    @PostMapping("/purchase-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PURCHASING')")
    PurchasingDtos.PurchaseOrderResponse create(@Valid @RequestBody PurchasingDtos.CreatePurchaseOrderRequest request,
            Actor actor) {
        return orders.create(request, actor);
    }

    @PutMapping("/purchase-orders/{id}")
    @PreAuthorize("hasRole('PURCHASING')")
    PurchasingDtos.PurchaseOrderResponse update(@PathVariable long id,
            @Valid @RequestBody PurchasingDtos.UpdatePurchaseOrderRequest request, Actor actor) {
        return orders.update(id, request, actor);
    }

    /** submit / approve / reject / cancel / close; the role each needs is checked in the service. */
    @PostMapping("/purchase-orders/{id}/actions/{action}")
    @PreAuthorize("hasAnyRole('PURCHASING', 'MANAGER')")
    PurchasingDtos.PurchaseOrderResponse act(@PathVariable long id, @PathVariable String action,
            @Valid @RequestBody(required = false) PurchasingDtos.ActionRequest request, Actor actor) {
        return orders.act(id, PurchaseOrderActions.parse(action), request == null ? null : request.comment(), actor);
    }

    @PostMapping("/purchase-orders/{id}/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('WAREHOUSE')")
    PurchasingDtos.GoodsReceiptResponse receive(@PathVariable long id, @Valid @RequestBody PurchasingDtos.ReceiptRequest request,
            Actor actor) {
        return receipts.receive(id, request, actor);
    }

    @GetMapping("/purchase-orders/{id}/receipts")
    List<PurchasingDtos.GoodsReceiptResponse> receiptsOf(@PathVariable long id) {
        return receipts.forOrder(id);
    }

    @GetMapping("/goods-receipts/{id}")
    PurchasingDtos.GoodsReceiptResponse receipt(@PathVariable long id) {
        return receipts.get(id);
    }
}
