package com.minierp.partners;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Someone we sell to. Their orders and invoices are in their currency. */
@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    private String email;

    @Column(name = "address_line1", nullable = false)
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    @Column(name = "postal_code", nullable = false)
    private String postalCode;

    @Column(nullable = false)
    private String city;

    @Column(name = "country_code", nullable = false)
    private String countryCode;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "payment_terms_days", nullable = false)
    private int paymentTermsDays;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Customer() {
        // for JPA
    }

    public Customer(String code, String name, String email, Address address, String currency, int paymentTermsDays,
            Instant createdAt) {
        this.code = code;
        this.name = name;
        this.email = email;
        this.addressLine1 = address.line1();
        this.addressLine2 = address.line2();
        this.postalCode = address.postalCode();
        this.city = address.city();
        this.countryCode = address.countryCode();
        this.currency = currency;
        this.paymentTermsDays = paymentTermsDays;
        this.createdAt = createdAt;
    }

    /** A postal address as one value. */
    public record Address(String line1, String line2, String postalCode, String city, String countryCode) {

        /** As printed on an invoice, one line per element. */
        public String asLines() {
            StringBuilder text = new StringBuilder(line1);
            if (line2 != null && !line2.isBlank()) {
                text.append('\n').append(line2);
            }
            return text.append('\n').append(postalCode).append(' ').append(city).append('\n').append(countryCode).toString();
        }
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public Address getAddress() {
        return new Address(addressLine1, addressLine2, postalCode, city, countryCode);
    }

    public String getCurrency() {
        return currency;
    }

    public int getPaymentTermsDays() {
        return paymentTermsDays;
    }

    public boolean isActive() {
        return active;
    }
}
