package com.minierp.partners;

import com.minierp.shared.BusinessCalendar;
import com.minierp.shared.Errors;
import com.minierp.shared.Money;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Suppliers and customers, and the partners module's public API for the other modules. */
@Service
@Transactional(readOnly = true)
public class PartnerService {

    /** What purchasing needs to know about a supplier. */
    public record SupplierInfo(long id, String code, String name, String currency, boolean active) {}

    /** What sales and invoicing need to know about a customer. */
    public record CustomerInfo(long id, String code, String name, Customer.Address address, String currency,
            int paymentTermsDays, boolean active) {}

    private final SupplierRepository suppliers;
    private final CustomerRepository customers;
    private final BusinessCalendar calendar;

    PartnerService(SupplierRepository suppliers, CustomerRepository customers,
            BusinessCalendar calendar) {
        this.suppliers = suppliers;
        this.customers = customers;
        this.calendar = calendar;
    }

    @Transactional
    public PartnerDtos.SupplierResponse createSupplier(PartnerDtos.CreateSupplierRequest request) {
        if (suppliers.existsByCode(request.code())) {
            throw Errors.BusinessRuleException.forField("duplicate_code", "code", "Supplier " + request.code() + " already exists.");
        }
        String currency = Money.requireCurrency(request.currency(), "currency");
        return toResponse(suppliers.save(new Supplier(request.code(), request.name(), request.email(), currency,
                request.paymentTermsDays(), calendar.now())));
    }

    @Transactional
    public PartnerDtos.CustomerResponse createCustomer(PartnerDtos.CreateCustomerRequest request) {
        if (customers.existsByCode(request.code())) {
            throw Errors.BusinessRuleException.forField("duplicate_code", "code", "Customer " + request.code() + " already exists.");
        }
        String currency = Money.requireCurrency(request.currency(), "currency");
        Customer.Address address = new Customer.Address(request.addressLine1(), request.addressLine2(),
                request.postalCode(), request.city(), request.countryCode());
        return toResponse(customers.save(new Customer(request.code(), request.name(), request.email(), address, currency,
                request.paymentTermsDays(), calendar.now())));
    }

    public List<PartnerDtos.SupplierResponse> suppliers() {
        return suppliers.findAllByOrderByCodeAsc().stream().map(PartnerService::toResponse).toList();
    }

    public List<PartnerDtos.CustomerResponse> customers() {
        return customers.findAllByOrderByCodeAsc().stream().map(PartnerService::toResponse).toList();
    }

    public PartnerDtos.SupplierResponse supplierResponse(long id) {
        return toResponse(suppliers.findById(id).orElseThrow(() -> new Errors.NotFoundException("Supplier", id)));
    }

    public PartnerDtos.CustomerResponse customerResponse(long id) {
        return toResponse(customers.findById(id).orElseThrow(() -> new Errors.NotFoundException("Customer", id)));
    }

    // ---- public API for other modules ---------------------------------------------------------

    /** The supplier, or a 422 on {@code field} if it doesn't exist or is inactive. */
    public SupplierInfo requireActiveSupplier(long id, String field) {
        Supplier supplier = suppliers.findById(id).orElseThrow(() -> Errors.BusinessRuleException.forField(
                "unknown_supplier", field, "Supplier " + id + " does not exist."));
        if (!supplier.isActive()) {
            throw Errors.BusinessRuleException.forField("inactive_supplier", field, "Supplier " + supplier.getCode() + " is inactive.");
        }
        return info(supplier);
    }

    /** The customer, or a 422 on {@code field} if it doesn't exist or is inactive. */
    public CustomerInfo requireActiveCustomer(long id, String field) {
        Customer customer = customers.findById(id).orElseThrow(() -> Errors.BusinessRuleException.forField(
                "unknown_customer", field, "Customer " + id + " does not exist."));
        if (!customer.isActive()) {
            throw Errors.BusinessRuleException.forField("inactive_customer", field, "Customer " + customer.getCode() + " is inactive.");
        }
        return info(customer);
    }

    public SupplierInfo supplier(long id) {
        return info(suppliers.findById(id).orElseThrow(() -> new Errors.NotFoundException("Supplier", id)));
    }

    public CustomerInfo customer(long id) {
        return info(customers.findById(id).orElseThrow(() -> new Errors.NotFoundException("Customer", id)));
    }

    // ---- mapping ------------------------------------------------------------------------------

    private static SupplierInfo info(Supplier s) {
        return new SupplierInfo(s.getId(), s.getCode(), s.getName(), s.getCurrency(), s.isActive());
    }

    private static CustomerInfo info(Customer c) {
        return new CustomerInfo(c.getId(), c.getCode(), c.getName(), c.getAddress(), c.getCurrency(),
                c.getPaymentTermsDays(), c.isActive());
    }

    private static PartnerDtos.SupplierResponse toResponse(Supplier s) {
        return new PartnerDtos.SupplierResponse(s.getId(), s.getCode(), s.getName(), s.getEmail(), s.getCurrency(),
                s.getPaymentTermsDays(), s.isActive());
    }

    private static PartnerDtos.CustomerResponse toResponse(Customer c) {
        return new PartnerDtos.CustomerResponse(c.getId(), c.getCode(), c.getName(), c.getEmail(), c.getAddress(),
                c.getCurrency(), c.getPaymentTermsDays(), c.isActive());
    }
}
