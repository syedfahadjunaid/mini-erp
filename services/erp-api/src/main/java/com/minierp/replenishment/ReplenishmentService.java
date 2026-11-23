package com.minierp.replenishment;

import com.minierp.catalog.CatalogService;
import com.minierp.inventory.StockQueries;
import com.minierp.inventory.WarehouseService;
import com.minierp.shared.Actor;
import com.minierp.shared.BusinessCalendar;
import com.minierp.shared.Errors;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reorder points and low-stock alerts, in plain SQL ({@link JdbcClient}): the work is a few
 * set-based statements ({@code INSERT ... ON CONFLICT}, conditional {@code UPDATE}s), which SQL
 * says more directly than entities would.
 *
 * <p>{@link #evaluate} is the heart: called after every stock change (via the
 * {@code StockLevelChanged} event) and whenever a rule changes. It is idempotent and
 * order-independent: it locks the rule row, re-reads the CURRENT available stock, and makes the
 * alert state match it. So a late, repeated or out-of-order event can't leave a stale alert.
 */
@Service
@Transactional(readOnly = true)
public class ReplenishmentService {

    private final JdbcClient jdbc;
    private final StockQueries stock;
    private final WarehouseService warehouses;
    private final CatalogService catalog;
    private final BusinessCalendar calendar;

    ReplenishmentService(JdbcClient jdbc, StockQueries stock, WarehouseService warehouses, CatalogService catalog,
            BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.warehouses = warehouses;
        this.catalog = catalog;
        this.calendar = calendar;
    }

    @Transactional
    public ReplenishmentDtos.ReorderRuleResponse setRule(long warehouseId, long productId, ReplenishmentDtos.ReorderRuleRequest rule,
            Actor actor) {
        warehouses.requireActiveWarehouse(warehouseId, "warehouseId");
        catalog.requireActiveProduct(productId, "productId");
        jdbc.sql("""
                        INSERT INTO reorder_rules (warehouse_id, product_id, reorder_point, reorder_quantity, updated_by, updated_at)
                        VALUES (:w, :p, :point, :qty, :actor, :at)
                        ON CONFLICT (warehouse_id, product_id) DO UPDATE
                           SET reorder_point = EXCLUDED.reorder_point,
                               reorder_quantity = EXCLUDED.reorder_quantity,
                               updated_by = EXCLUDED.updated_by,
                               updated_at = EXCLUDED.updated_at,
                               version = reorder_rules.version + 1""")
                .param("w", warehouseId)
                .param("p", productId)
                .param("point", rule.reorderPoint())
                .param("qty", rule.reorderQuantity())
                .param("actor", actor.username())
                .param("at", utc(calendar.now()))
                .update();
        evaluate(warehouseId, productId); // a new or raised reorder point may mean stock is low right now
        return rules(warehouseId).stream().filter(r -> r.productId() == productId).findFirst().orElseThrow();
    }

    /**
     * Makes the alert state for one warehouse and product match the current stock:
     * available at or below the reorder point means an unresolved alert exists, above means none does.
     */
    @Transactional
    public void evaluate(long warehouseId, long productId) {
        // Lock the rule: evaluations of the same product run one at a time, and each reads the
        // latest committed stock AFTER getting the lock. Without it, a slow evaluation that read
        // "low" before a receipt could raise an alert after a faster one had cleared it.
        Optional<Long> reorderPoint = jdbc.sql("""
                        SELECT reorder_point FROM reorder_rules WHERE warehouse_id = :w AND product_id = :p FOR UPDATE""")
                .param("w", warehouseId)
                .param("p", productId)
                .query(Long.class)
                .optional();
        if (reorderPoint.isEmpty()) {
            return; // no rule, nothing to watch
        }
        long available = stock.level(warehouseId, productId).available();
        Instant now = calendar.now();
        if (available <= reorderPoint.get()) {
            // At most one unresolved alert per product and warehouse (partial unique index).
            jdbc.sql("""
                            INSERT INTO low_stock_alerts (warehouse_id, product_id, status, reorder_point, available_at_open, raised_at)
                            VALUES (:w, :p, 'OPEN', :point, :available, :at)
                            ON CONFLICT (warehouse_id, product_id) WHERE status <> 'RESOLVED' DO NOTHING""")
                    .param("w", warehouseId)
                    .param("p", productId)
                    .param("point", reorderPoint.get())
                    .param("available", available)
                    .param("at", utc(now))
                    .update();
        } else {
            jdbc.sql("""
                            UPDATE low_stock_alerts SET status = 'RESOLVED', resolved_at = :at, version = version + 1
                             WHERE warehouse_id = :w AND product_id = :p AND status <> 'RESOLVED'""")
                    .param("w", warehouseId)
                    .param("p", productId)
                    .param("at", utc(now))
                    .update();
        }
    }

    /** Someone has seen the alert and is handling it (raising a purchase order, say). */
    @Transactional
    public ReplenishmentDtos.AlertResponse acknowledge(long alertId, Actor actor) {
        AlertStatus current = jdbc.sql("SELECT status FROM low_stock_alerts WHERE id = :id FOR UPDATE")
                .param("id", alertId)
                .query((rs, row) -> AlertStatus.valueOf(rs.getString(1)))
                .optional()
                .orElseThrow(() -> new Errors.NotFoundException("Alert", alertId));
        AlertStatus next = current.after(AlertStatus.Action.ACKNOWLEDGE); // 409 unless OPEN
        jdbc.sql("""
                        UPDATE low_stock_alerts SET status = :status, acknowledged_by = :actor, acknowledged_at = :at,
                               version = version + 1
                         WHERE id = :id""")
                .param("status", next.name())
                .param("actor", actor.username())
                .param("at", utc(calendar.now()))
                .param("id", alertId)
                .update();
        return alerts(null, null).stream().filter(a -> a.id() == alertId).findFirst().orElseThrow();
    }

    public List<ReplenishmentDtos.AlertResponse> alerts(AlertStatus status, Long warehouseId) {
        return jdbc.sql("""
                        SELECT a.*, w.code AS warehouse_code, p.sku, p.name AS product_name, r.reorder_quantity,
                               COALESCE(s.available, 0) AS available_now
                          FROM low_stock_alerts a
                          JOIN warehouses w ON w.id = a.warehouse_id
                          JOIN products p ON p.id = a.product_id
                          JOIN reorder_rules r ON r.warehouse_id = a.warehouse_id AND r.product_id = a.product_id
                          LEFT JOIN stock_levels s ON s.warehouse_id = a.warehouse_id AND s.product_id = a.product_id
                         WHERE (CAST(:status AS VARCHAR) IS NULL OR a.status = :status)
                           AND (CAST(:warehouse AS BIGINT) IS NULL OR a.warehouse_id = :warehouse)
                         ORDER BY a.id DESC
                         LIMIT 500""")
                .param("status", status == null ? null : status.name())
                .param("warehouse", warehouseId)
                .query(ReplenishmentService::alert)
                .list();
    }

    public List<ReplenishmentDtos.ReorderRuleResponse> rules(Long warehouseId) {
        return jdbc.sql("""
                        SELECT r.*, w.code AS warehouse_code, p.sku, COALESCE(s.available, 0) AS available
                          FROM reorder_rules r
                          JOIN warehouses w ON w.id = r.warehouse_id
                          JOIN products p ON p.id = r.product_id
                          LEFT JOIN stock_levels s ON s.warehouse_id = r.warehouse_id AND s.product_id = r.product_id
                         WHERE CAST(:warehouse AS BIGINT) IS NULL OR r.warehouse_id = :warehouse
                         ORDER BY w.code, p.sku""")
                .param("warehouse", warehouseId)
                .query((rs, row) -> new ReplenishmentDtos.ReorderRuleResponse(rs.getLong("warehouse_id"),
                        rs.getString("warehouse_code"), rs.getLong("product_id"), rs.getString("sku"),
                        rs.getLong("reorder_point"), rs.getLong("reorder_quantity"), rs.getLong("available"),
                        rs.getString("updated_by"), instant(rs, "updated_at")))
                .list();
    }

    private static ReplenishmentDtos.AlertResponse alert(ResultSet rs, int row) throws SQLException {
        return new ReplenishmentDtos.AlertResponse(rs.getLong("id"), rs.getLong("warehouse_id"), rs.getString("warehouse_code"),
                rs.getLong("product_id"), rs.getString("sku"), rs.getString("product_name"),
                AlertStatus.valueOf(rs.getString("status")), rs.getLong("reorder_point"), rs.getLong("reorder_quantity"),
                rs.getLong("available_at_open"), rs.getLong("available_now"), instant(rs, "raised_at"),
                rs.getString("acknowledged_by"), instant(rs, "acknowledged_at"), instant(rs, "resolved_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
