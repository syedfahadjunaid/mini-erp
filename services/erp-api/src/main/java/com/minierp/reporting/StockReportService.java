package com.minierp.reporting;

import com.minierp.inventory.WarehouseService;
import com.minierp.shared.BusinessCalendar;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the stock report: current balances, or balances as of a past instant.
 *
 * <p>The as-of variant is the "reconstruct from the ledger" capability: it ignores the balance
 * tables entirely and sums the movements up to that instant. For the present moment both ways
 * give the same numbers (the reconciliation report checks exactly that).
 *
 * <p>Reporting is a read model: it reads several modules' tables with SQL joins, and never
 * writes. That's why it may depend on everything and nothing depends on it.
 */
@Service
@Transactional(readOnly = true)
public class StockReportService {

    private static final String CURRENT = """
            SELECT w.code AS warehouse_code, p.sku, p.name AS product_name, p.base_uom,
                   s.on_hand, s.reserved, s.available, r.reorder_point, r.reorder_quantity
              FROM stock_levels s
              JOIN warehouses w ON w.id = s.warehouse_id
              JOIN products p ON p.id = s.product_id
              LEFT JOIN reorder_rules r ON r.warehouse_id = s.warehouse_id AND r.product_id = s.product_id
             WHERE CAST(:warehouse AS BIGINT) IS NULL OR s.warehouse_id = :warehouse
             ORDER BY w.code, p.sku""";

    private static final String AS_OF = """
            WITH ledger AS (
                SELECT warehouse_id, product_id, SUM(on_hand_delta) AS on_hand, SUM(reserved_delta) AS reserved
                  FROM stock_movements
                 WHERE occurred_at <= :asOf
                   AND (CAST(:warehouse AS BIGINT) IS NULL OR warehouse_id = :warehouse)
                 GROUP BY warehouse_id, product_id)
            SELECT w.code AS warehouse_code, p.sku, p.name AS product_name, p.base_uom,
                   l.on_hand, l.reserved, l.on_hand - l.reserved AS available, r.reorder_point, r.reorder_quantity
              FROM ledger l
              JOIN warehouses w ON w.id = l.warehouse_id
              JOIN products p ON p.id = l.product_id
              LEFT JOIN reorder_rules r ON r.warehouse_id = l.warehouse_id AND r.product_id = l.product_id
             ORDER BY w.code, p.sku""";

    private final JdbcClient jdbc;
    private final WarehouseService warehouses;
    private final BusinessCalendar calendar;

    StockReportService(JdbcClient jdbc, WarehouseService warehouses, BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.warehouses = warehouses;
        this.calendar = calendar;
    }

    public StockReport build(Long warehouseId, Instant asOf) {
        String warehouseLabel = warehouseId == null ? "All warehouses" : warehouses.warehouse(warehouseId).code();
        List<StockReport.Row> rows = asOf == null
                ? jdbc.sql(CURRENT).param("warehouse", warehouseId).query(StockReportService::row).list()
                : jdbc.sql(AS_OF).param("warehouse", warehouseId).param("asOf", OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC))
                        .query(StockReportService::row).list();
        return new StockReport(calendar.now(), asOf, warehouseLabel, rows);
    }

    private static StockReport.Row row(ResultSet rs, int rowNum) throws SQLException {
        long point = rs.getLong("reorder_point");
        Long reorderPoint = rs.wasNull() ? null : point;
        long quantity = rs.getLong("reorder_quantity");
        Long reorderQuantity = rs.wasNull() ? null : quantity;
        return new StockReport.Row(rs.getString("warehouse_code"), rs.getString("sku"), rs.getString("product_name"),
                rs.getString("base_uom"), rs.getLong("on_hand"), rs.getLong("reserved"), rs.getLong("available"),
                reorderPoint, reorderQuantity);
    }
}
