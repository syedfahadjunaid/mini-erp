package com.minierp.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * A stock-keeping unit (SKU). Its base unit of measure is fixed at creation: every stock
 * quantity in the ledger is expressed in it.
 *
 * <p>{@code updatable = false} mirrors the database grants: the app login may only UPDATE the
 * columns that are allowed to change (see V1__master_data.sql), and Hibernate must not try to
 * write the others.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(name = "base_uom", nullable = false, updatable = false)
    private String baseUom;

    @Column(name = "tax_rate_bp", nullable = false)
    private int taxRateBp;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected Product() {
        // for JPA
    }

    public Product(String sku, String name, String baseUom, int taxRateBp, Instant createdAt) {
        this.sku = sku;
        this.name = name;
        this.baseUom = baseUom;
        this.taxRateBp = taxRateBp;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getBaseUom() {
        return baseUom;
    }

    public int getTaxRateBp() {
        return taxRateBp;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
