package com.minierp.partners;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Suppliers and customers. Everyone signed in can read; only ADMIN creates. */
@RestController
@RequestMapping("/api")
class PartnerController {

    private final PartnerService partners;

    PartnerController(PartnerService partners) {
        this.partners = partners;
    }

    @GetMapping("/suppliers")
    List<PartnerDtos.SupplierResponse> suppliers() {
        return partners.suppliers();
    }

    @GetMapping("/suppliers/{id}")
    PartnerDtos.SupplierResponse supplier(@PathVariable long id) {
        return partners.supplierResponse(id);
    }

    @PostMapping("/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    PartnerDtos.SupplierResponse createSupplier(@Valid @RequestBody PartnerDtos.CreateSupplierRequest request) {
        return partners.createSupplier(request);
    }

    @GetMapping("/customers")
    List<PartnerDtos.CustomerResponse> customers() {
        return partners.customers();
    }

    @GetMapping("/customers/{id}")
    PartnerDtos.CustomerResponse customer(@PathVariable long id) {
        return partners.customerResponse(id);
    }

    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    PartnerDtos.CustomerResponse createCustomer(@Valid @RequestBody PartnerDtos.CreateCustomerRequest request) {
        return partners.createCustomer(request);
    }
}
