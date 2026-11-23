package com.minierp.sales;

import com.minierp.shared.Actor;
import com.minierp.shared.Errors;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
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

/** Sales orders (SALES) and their shipments (WAREHOUSE). Everyone signed in can read. */
@RestController
@RequestMapping("/api")
class SalesController {

    private final SalesOrderService orders;
    private final ShippingService shipping;

    SalesController(SalesOrderService orders, ShippingService shipping) {
        this.orders = orders;
        this.shipping = shipping;
    }

    @GetMapping("/sales-orders")
    List<SalesDtos.SalesOrderResponse> list(@RequestParam(required = false) SalesOrderStatus status, Actor actor) {
        return orders.list(status, actor);
    }

    @GetMapping("/sales-orders/{id}")
    SalesDtos.SalesOrderResponse get(@PathVariable long id, Actor actor) {
        return orders.get(id, actor);
    }

    @PostMapping("/sales-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SALES')")
    SalesDtos.SalesOrderResponse create(@Valid @RequestBody SalesDtos.CreateSalesOrderRequest request, Actor actor) {
        return orders.create(request, actor);
    }

    @PutMapping("/sales-orders/{id}")
    @PreAuthorize("hasRole('SALES')")
    SalesDtos.SalesOrderResponse update(@PathVariable long id, @Valid @RequestBody SalesDtos.UpdateSalesOrderRequest request,
            Actor actor) {
        return orders.update(id, request, actor);
    }

    /** confirm / cancel / close. */
    @PostMapping("/sales-orders/{id}/actions/{action}")
    @PreAuthorize("hasRole('SALES')")
    SalesDtos.SalesOrderResponse act(@PathVariable long id, @PathVariable String action,
            @Valid @RequestBody(required = false) SalesDtos.ActionRequest request, Actor actor) {
        return orders.act(id, parse(action), request == null ? null : request.comment(), actor);
    }

    @PostMapping("/sales-orders/{id}/shipments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('WAREHOUSE')")
    SalesDtos.ShipmentResponse ship(@PathVariable long id, @Valid @RequestBody SalesDtos.ShipmentRequest request, Actor actor) {
        return shipping.ship(id, request, actor);
    }

    @GetMapping("/sales-orders/{id}/shipments")
    List<SalesDtos.ShipmentResponse> shipmentsOf(@PathVariable long id) {
        return shipping.forOrder(id);
    }

    @GetMapping("/shipments/{id}")
    SalesDtos.ShipmentResponse shipment(@PathVariable long id) {
        return shipping.get(id);
    }

    private static SalesOrderAction parse(String segment) {
        try {
            return SalesOrderAction.valueOf(segment.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Errors.NotFoundException("Action", segment);
        }
    }
}
