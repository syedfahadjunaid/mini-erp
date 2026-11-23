package com.minierp.sales;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/** One status change of a sales order: who, when, from what to what. Append-only. */
@Entity
@Immutable
@Table(name = "sales_order_history")
public class SalesOrderHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sales_order_id", nullable = false)
    private long salesOrderId;

    @Column(nullable = false)
    private String action;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status", nullable = false)
    private String toStatus;

    @Column(nullable = false)
    private String actor;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    private String comment;

    protected SalesOrderHistory() {
        // for JPA
    }

    SalesOrderHistory(long salesOrderId, String action, SalesOrderStatus fromStatus, SalesOrderStatus toStatus,
            String actor, Instant occurredAt, String comment) {
        this.salesOrderId = salesOrderId;
        this.action = action;
        this.fromStatus = fromStatus == null ? null : fromStatus.name();
        this.toStatus = toStatus.name();
        this.actor = actor;
        this.occurredAt = occurredAt;
        this.comment = comment;
    }

    public String getAction() {
        return action;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public String getActor() {
        return actor;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getComment() {
        return comment;
    }
}
