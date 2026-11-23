package com.minierp.reporting;

import com.minierp.shared.BusinessCalendar;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stock audit: recomputes everything from the append-only ledger and compares it with the
 * stored balances and with the documents. A healthy system always reports "balanced"; tests run
 * it after every scenario, including after hundreds of concurrent operations.
 *
 * <p>Checks:
 * <ol>
 *   <li>{@code LEVEL}: each (warehouse, product) balance equals the sum of its movements, and
 *       counts as many movements as there are;</li>
 *   <li>{@code BIN}: each bin balance equals the sum of that bin's movements;</li>
 *   <li>{@code BINS_VS_LEVEL}: the bins of a warehouse add up to its on-hand balance;</li>
 *   <li>{@code RUNNING_BALANCE}: every movement's recorded "after" balances equal the running
 *       totals, and its sequence numbers run 1, 2, 3, ... without gaps;</li>
 *   <li>{@code RESERVATION}: what the ledger holds reserved for each sales order line equals what
 *       the order still needs (open quantity while confirmed, zero otherwise);</li>
 *   <li>{@code RECEIPT} / {@code SHIPMENT}: received and shipped quantities on order lines equal
 *       the ledger's receipt and shipment movements.</li>
 * </ol>
 *
 * <p>Runs in a REPEATABLE READ transaction, so all queries see the same snapshot even while
 * other transactions keep moving stock.
 */
@Service
public class ReconciliationService {

    /** One mismatch. {@code reference} identifies the row (e.g. "sales_order_line 42"). */
    public record Discrepancy(String check, Long warehouseId, Long productId, String reference, long expected, long actual) {}

    /** Units of a product on the road between warehouses (left the source, not yet received). */
    public record InTransit(long productId, long quantity) {}

    public record Report(boolean balanced, Instant checkedAt, long movementsChecked, List<Discrepancy> discrepancies,
            List<InTransit> inTransit) {}

    private final JdbcClient jdbc;
    private final BusinessCalendar calendar;

    ReconciliationService(JdbcClient jdbc, BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.calendar = calendar;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report run() {
        List<Discrepancy> found = new ArrayList<>();

        // 1. Balances vs. ledger, per warehouse and product (on hand, reserved, movement count).
        jdbc.sql("""
                        WITH ledger AS (
                            SELECT warehouse_id, product_id, SUM(on_hand_delta) AS on_hand,
                                   SUM(reserved_delta) AS reserved, COUNT(*) AS movements
                              FROM stock_movements GROUP BY warehouse_id, product_id)
                        SELECT COALESCE(l.warehouse_id, s.warehouse_id) AS warehouse_id,
                               COALESCE(l.product_id, s.product_id) AS product_id,
                               COALESCE(l.on_hand, 0) AS l_on_hand, COALESCE(s.on_hand, 0) AS s_on_hand,
                               COALESCE(l.reserved, 0) AS l_reserved, COALESCE(s.reserved, 0) AS s_reserved,
                               COALESCE(l.movements, 0) AS l_count, COALESCE(s.movement_count, 0) AS s_count
                          FROM ledger l
                          FULL JOIN stock_levels s ON s.warehouse_id = l.warehouse_id AND s.product_id = l.product_id""")
                .query((RowCallbackHandler) rs -> {
                    long w = rs.getLong("warehouse_id");
                    long p = rs.getLong("product_id");
                    compare(found, "LEVEL_ON_HAND", w, p, "stock_level", rs.getLong("l_on_hand"), rs.getLong("s_on_hand"));
                    compare(found, "LEVEL_RESERVED", w, p, "stock_level", rs.getLong("l_reserved"), rs.getLong("s_reserved"));
                    compare(found, "LEVEL_MOVEMENT_COUNT", w, p, "stock_level", rs.getLong("l_count"), rs.getLong("s_count"));
                });

        // 2. Bin balances vs. ledger.
        jdbc.sql("""
                        WITH ledger AS (
                            SELECT warehouse_id, bin_id, product_id, SUM(on_hand_delta) AS on_hand
                              FROM stock_movements WHERE bin_id IS NOT NULL GROUP BY warehouse_id, bin_id, product_id)
                        SELECT COALESCE(l.warehouse_id, b.warehouse_id) AS warehouse_id,
                               COALESCE(l.product_id, b.product_id) AS product_id,
                               COALESCE(l.bin_id, b.bin_id) AS bin_id,
                               COALESCE(l.on_hand, 0) AS l_on_hand, COALESCE(b.on_hand, 0) AS b_on_hand
                          FROM ledger l
                          FULL JOIN bin_stock b ON b.bin_id = l.bin_id AND b.product_id = l.product_id
                         WHERE COALESCE(l.on_hand, 0) <> COALESCE(b.on_hand, 0)""")
                .query((RowCallbackHandler) rs -> {
                    found.add(new Discrepancy("BIN", rs.getLong("warehouse_id"), rs.getLong("product_id"),
                            "bin " + rs.getLong("bin_id"), rs.getLong("l_on_hand"), rs.getLong("b_on_hand")));
                });

        // 3. The bins of a warehouse add up to its on-hand balance.
        jdbc.sql("""
                        SELECT s.warehouse_id, s.product_id, s.on_hand, COALESCE(SUM(b.on_hand), 0) AS in_bins
                          FROM stock_levels s
                          LEFT JOIN bin_stock b ON b.warehouse_id = s.warehouse_id AND b.product_id = s.product_id
                         GROUP BY s.warehouse_id, s.product_id, s.on_hand
                        HAVING s.on_hand <> COALESCE(SUM(b.on_hand), 0)""")
                .query((RowCallbackHandler) rs -> {
                    found.add(new Discrepancy("BINS_VS_LEVEL", rs.getLong("warehouse_id"), rs.getLong("product_id"),
                            "stock_level", rs.getLong("on_hand"), rs.getLong("in_bins")));
                });

        // 4. Running balances and gap-free sequence numbers per (warehouse, product).
        jdbc.sql("""
                        SELECT id, warehouse_id, product_id, level_seq, on_hand_after, reserved_after,
                               running_on_hand, running_reserved, expected_seq
                          FROM (SELECT m.*,
                                       SUM(on_hand_delta) OVER w AS running_on_hand,
                                       SUM(reserved_delta) OVER w AS running_reserved,
                                       ROW_NUMBER() OVER w AS expected_seq
                                  FROM stock_movements m
                                WINDOW w AS (PARTITION BY warehouse_id, product_id ORDER BY level_seq)) x
                         WHERE on_hand_after <> running_on_hand OR reserved_after <> running_reserved
                            OR level_seq <> expected_seq""")
                .query((RowCallbackHandler) rs -> {
                    String ref = "movement " + rs.getLong("id");
                    long w = rs.getLong("warehouse_id");
                    long p = rs.getLong("product_id");
                    compare(found, "RUNNING_ON_HAND", w, p, ref, rs.getLong("running_on_hand"), rs.getLong("on_hand_after"));
                    compare(found, "RUNNING_RESERVED", w, p, ref, rs.getLong("running_reserved"), rs.getLong("reserved_after"));
                    compare(found, "SEQUENCE", w, p, ref, rs.getLong("expected_seq"), rs.getLong("level_seq"));
                });

        // 5. Reservations held in the ledger vs. what each sales order line still needs.
        jdbc.sql("""
                        WITH held AS (
                            SELECT COALESCE(m.sales_order_line_id, sl.sales_order_line_id) AS line_id,
                                   SUM(m.reserved_delta) AS reserved
                              FROM stock_movements m
                              LEFT JOIN shipment_lines sl ON sl.id = m.shipment_line_id
                             WHERE m.sales_order_line_id IS NOT NULL OR m.shipment_line_id IS NOT NULL
                             GROUP BY 1)
                        SELECT l.id, l.warehouse_id, l.product_id, COALESCE(h.reserved, 0) AS held,
                               CASE WHEN o.status IN ('CONFIRMED', 'PARTIALLY_SHIPPED')
                                    THEN l.base_quantity - l.shipped_base_quantity ELSE 0 END AS needed
                          FROM sales_order_lines l
                          JOIN sales_orders o ON o.id = l.sales_order_id
                          LEFT JOIN held h ON h.line_id = l.id""")
                .query((RowCallbackHandler) rs -> compare(found, "RESERVATION", rs.getLong("warehouse_id"), rs.getLong("product_id"),
                        "sales_order_line " + rs.getLong("id"), rs.getLong("needed"), rs.getLong("held")));

        // 6a. Received quantities on purchase order lines vs. receipt movements.
        jdbc.sql("""
                        SELECT pl.id, po.warehouse_id, pl.product_id, pl.received_base_quantity,
                               COALESCE(SUM(m.on_hand_delta), 0) AS booked
                          FROM purchase_order_lines pl
                          JOIN purchase_orders po ON po.id = pl.purchase_order_id
                          LEFT JOIN goods_receipt_lines gl ON gl.purchase_order_line_id = pl.id
                          LEFT JOIN stock_movements m ON m.goods_receipt_line_id = gl.id
                         GROUP BY pl.id, po.warehouse_id, pl.product_id, pl.received_base_quantity
                        HAVING pl.received_base_quantity <> COALESCE(SUM(m.on_hand_delta), 0)""")
                .query((RowCallbackHandler) rs -> {
                    found.add(new Discrepancy("RECEIPT", rs.getLong("warehouse_id"), rs.getLong("product_id"),
                            "purchase_order_line " + rs.getLong("id"), rs.getLong("received_base_quantity"), rs.getLong("booked")));
                });

        // 6b. Shipped quantities on sales order lines vs. shipment movements.
        jdbc.sql("""
                        SELECT l.id, l.warehouse_id, l.product_id, l.shipped_base_quantity,
                               COALESCE(-SUM(m.on_hand_delta), 0) AS booked
                          FROM sales_order_lines l
                          LEFT JOIN shipment_lines sl ON sl.sales_order_line_id = l.id
                          LEFT JOIN stock_movements m ON m.shipment_line_id = sl.id
                         GROUP BY l.id, l.warehouse_id, l.product_id, l.shipped_base_quantity
                        HAVING l.shipped_base_quantity <> COALESCE(-SUM(m.on_hand_delta), 0)""")
                .query((RowCallbackHandler) rs -> {
                    found.add(new Discrepancy("SHIPMENT", rs.getLong("warehouse_id"), rs.getLong("product_id"),
                            "sales_order_line " + rs.getLong("id"), rs.getLong("shipped_base_quantity"), rs.getLong("booked")));
                });

        long movements = jdbc.sql("SELECT COUNT(*) FROM stock_movements").query(Long.class).single();
        List<InTransit> inTransit = jdbc.sql("""
                        SELECT l.product_id, SUM(l.quantity) AS quantity
                          FROM stock_transfer_lines l JOIN stock_transfers t ON t.id = l.transfer_id
                         WHERE t.status = 'IN_TRANSIT'
                         GROUP BY l.product_id ORDER BY l.product_id""")
                .query((rs, row) -> new InTransit(rs.getLong("product_id"), rs.getLong("quantity")))
                .list();
        return new Report(found.isEmpty(), calendar.now(), movements, found, inTransit);
    }

    private static void compare(List<Discrepancy> found, String check, long warehouseId, long productId, String reference,
            long expected, long actual) {
        if (expected != actual) {
            found.add(new Discrepancy(check, warehouseId, productId, reference, expected, actual));
        }
    }
}
