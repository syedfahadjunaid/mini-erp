package com.minierp.partners;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Someone we buy from. Purchase orders default to the supplier's currency. */
@Entity
@Table(name = "suppliers")
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    private String email;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "payment_terms_days", nullable = false)
    private int paymentTermsDays;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Supplier() {
        // for JPA
    }

    public Supplier(String code, String name, String email, String currency, int paymentTermsDays, Instant createdAt) {
        this.code = code;
        this.name = name;
        this.email = email;
        this.currency = currency;
        this.paymentTermsDays = paymentTermsDays;
        this.createdAt = createdAt;
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
