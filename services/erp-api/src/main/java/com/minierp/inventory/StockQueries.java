package com.minierp.inventory;

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
 * Read side of the stock ledger: balances, bins and movement history. Also used by other
 * modules (sales checks availability and picks bins; replenishment reads available stock).
 */
@Service
@Transactional(readOnly = true)
public class StockQueries {

    /** A balance snapshot for one warehouse and product. */
    public record Level(long onHand, long reserved, long available) {
        public static final Level EMPTY = new Level(0, 0, 0);
    }

    /** Stock of a product in one bin. */
    public record BinQuantity(long binId, String binCode, long onHand) {}

    private static final String LEVELS_SELECT = """
            SELECT s.warehouse_id, w.code AS warehouse_code, s.product_id, p.sku, p.name AS product_name, p.base_uom,
                   s.on_hand, s.reserved, s.available, s.updated_at
              FROM stock_levels s
              JOIN warehouses w ON w.id = s.warehouse_id
              JOIN products p ON p.id = s.product_id
            """;

    private final JdbcClient jdbc;

    StockQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Current balances, optionally for one warehouse and/or product. */
    public List<StockDtos.StockLevelView> levels(Long warehouseId, Long productId) {
        return jdbc.sql(LEVELS_SELECT + """
                         WHERE (CAST(:warehouse AS BIGINT) IS NULL OR s.warehouse_id = :warehouse)
                           AND (CAST(:product AS BIGINT) IS NULL OR s.product_id = :product)
                         ORDER BY w.code, p.sku""")
                .param("warehouse", warehouseId)
                .param("product", productId)
                .query(StockQueries::levelView)
                .list();
    }

    /** The balance of one product in one warehouse (zeros if it never had any). */
    public Level level(long warehouseId, long productId) {
        return jdbc.sql("SELECT on_hand, reserved, available FROM stock_levels WHERE warehouse_id = :w AND product_id = :p")
                .param("w", warehouseId)
                .param("p", productId)
                .query((rs, row) -> new Level(rs.getLong("on_hand"), rs.getLong("reserved"), rs.getLong("available")))
                .optional()
                .orElse(Level.EMPTY);
    }

    public List<StockDtos.BinStockView> bins(Long warehouseId, Long productId) {
        return jdbc.sql("""
                        SELECT b.warehouse_id, b.bin_id, l.code AS bin_code, b.product_id, p.sku, b.on_hand
                          FROM bin_stock b
                          JOIN bin_locations l ON l.id = b.bin_id
                          JOIN products p ON p.id = b.product_id
                         WHERE b.on_hand > 0
                           AND (CAST(:warehouse AS BIGINT) IS NULL OR b.warehouse_id = :warehouse)
                           AND (CAST(:product AS BIGINT) IS NULL OR b.product_id = :product)
                         ORDER BY b.warehouse_id, l.code, p.sku""")
                .param("warehouse", warehouseId)
                .param("product", productId)
                .query((rs, row) -> new StockDtos.BinStockView(rs.getLong("warehouse_id"), rs.getLong("bin_id"),
                        rs.getString("bin_code"), rs.getLong("product_id"), rs.getString("sku"), rs.getLong("on_hand")))
                .list();
    }

    /**
     * Bins holding the product, in picking order (bin code). Not locked: if another shipment takes
     * the stock meanwhile, the ledger trigger refuses the movement and the caller retries.
     */
    public List<BinQuantity> binsWithStock(long warehouseId, long productId) {
        return jdbc.sql("""
                        SELECT b.bin_id, l.code, b.on_hand
                          FROM bin_stock b JOIN bin_locations l ON l.id = b.bin_id
                         WHERE b.warehouse_id = :w AND b.product_id = :p AND b.on_hand > 0 AND l.active
                         ORDER BY l.code""")
                .param("w", warehouseId)
                .param("p", productId)
                .query((rs, row) -> new BinQuantity(rs.getLong("bin_id"), rs.getString("code"), rs.getLong("on_hand")))
                .list();
    }

    /** Newest first. {@code limit} is capped at 500. */
    public List<StockDtos.MovementView> movements(Long warehouseId, Long productId, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        SELECT m.*, p.sku FROM stock_movements m JOIN products p ON p.id = m.product_id
                         WHERE (CAST(:warehouse AS BIGINT) IS NULL OR m.warehouse_id = :warehouse)
                           AND (CAST(:product AS BIGINT) IS NULL OR m.product_id = :product)
                           AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR m.occurred_at >= :from)
                           AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR m.occurred_at < :to)
                         ORDER BY m.id DESC
                         LIMIT :limit""")
                .param("warehouse", warehouseId)
                .param("product", productId)
                .param("from", from == null ? null : OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", to == null ? null : OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .param("limit", Math.clamp(limit, 1, 500))
                .query(StockQueries::movementView)
                .list();
    }

    // ---- row mappers --------------------------------------------------------------------------

    private static StockDtos.StockLevelView levelView(ResultSet rs, int row) throws SQLException {
        return new StockDtos.StockLevelView(rs.getLong("warehouse_id"), rs.getString("warehouse_code"),
                rs.getLong("product_id"), rs.getString("sku"), rs.getString("product_name"), rs.getString("base_uom"),
                rs.getLong("on_hand"), rs.getLong("reserved"), rs.getLong("available"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    static StockDtos.MovementView movementView(ResultSet rs, int row) throws SQLException {
        MovementType type = MovementType.valueOf(rs.getString("movement_type"));
        long binId = rs.getLong("bin_id");
        Long bin = rs.wasNull() ? null : binId;
        return new StockDtos.MovementView(rs.getLong("id"), type.name(), rs.getLong("warehouse_id"), bin,
                rs.getLong("product_id"), rs.getString("sku"), rs.getLong("on_hand_delta"), rs.getLong("reserved_delta"),
                rs.getLong("level_seq"), rs.getLong("on_hand_after"), rs.getLong("reserved_after"),
                type.sourceColumn(), rs.getLong(type.sourceColumn()),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(), rs.getString("recorded_by"));
    }
}
