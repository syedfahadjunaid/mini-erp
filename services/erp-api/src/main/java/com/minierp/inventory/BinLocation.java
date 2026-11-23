package com.minierp.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A place inside one warehouse ("A-01-03"). The warehouse is referenced by id rather than as
 * a JPA association: bins are looked up per warehouse anyway, and plain ids keep the entity simple.
 */
@Entity
@Table(name = "bin_locations")
public class BinLocation {

    /** Code of the bin every new warehouse gets, where goods land unless told otherwise. */
    public static final String RECEIVING_CODE = "RECEIVING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private long warehouseId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(name = "is_receiving", nullable = false, updatable = false)
    private boolean receiving;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BinLocation() {
        // for JPA
    }

    public BinLocation(long warehouseId, String code, boolean receiving, Instant createdAt) {
        this.warehouseId = warehouseId;
        this.code = code;
        this.receiving = receiving;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public long getWarehouseId() {
        return warehouseId;
    }

    public String getCode() {
        return code;
    }

    public boolean isReceiving() {
        return receiving;
    }

    public boolean isActive() {
        return active;
    }
}
