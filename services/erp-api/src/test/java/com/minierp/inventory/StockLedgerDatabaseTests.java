package com.minierp.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minierp.IntegrationTest;
import com.minierp.TestUsers;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;

/**
 * The database's own guarantees, tested as the application's login ({@code erp_app}), i.e. with
 * exactly the privileges the running service has. These hold even if Java code is wrong.
 */
class StockLedgerDatabaseTests extends IntegrationTest {

    @Test
    void theLedgerIsAppendOnly() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        stockUp(warehouse.id(), warehouse.binA(), product, 5);
        long movementId = jdbc.sql("SELECT max(id) FROM stock_movements WHERE product_id = :p").param("p", product)
                .query(Long.class).single();

        assertThatThrownBy(() -> jdbc.sql("UPDATE stock_movements SET on_hand_delta = 500 WHERE id = :id")
                .param("id", movementId).update())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM stock_movements WHERE id = :id").param("id", movementId).update())
                .hasStackTraceContaining("permission denied");
    }

    @Test
    void balancesCanOnlyChangeThroughTheLedger() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        stockUp(warehouse.id(), warehouse.binA(), product, 5);

        assertThatThrownBy(() -> jdbc.sql("UPDATE stock_levels SET on_hand = 1000 WHERE product_id = :p")
                .param("p", product).update())
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql("UPDATE bin_stock SET on_hand = 1000 WHERE product_id = :p")
                .param("p", product).update())
                .hasStackTraceContaining("permission denied");
    }

    @Test
    void theTriggerKeepsBalancesAndRunningTotals() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        stockUp(warehouse.id(), warehouse.binA(), product, 10);
        stockUp(warehouse.id(), warehouse.binB(), product, 4);
        post("/api/stock/adjustments", TestUsers.MANAGER, Map.of("warehouseId", warehouse.id(), "binId", warehouse.binA(),
                "productId", product, "quantity", -3, "reason", "Damaged in handling"));

        JsonNode movements = get("/api/stock/movements?productId=" + product, TestUsers.MANAGER);
        // Newest first: seq 3, 2, 1, each carrying the balance right after it.
        assertThat(movements).extracting(m -> m.get("levelSeq").asLong()).containsExactly(3L, 2L, 1L);
        assertThat(movements).extracting(m -> m.get("onHandAfter").asLong()).containsExactly(11L, 14L, 10L);
        assertThat(movements.get(0).get("recordedBy").asString()).isEqualTo("max");

        JsonNode bins = get("/api/stock/bins?productId=" + product, TestUsers.WAREHOUSE);
        assertThat(bins).extracting(b -> b.get("binCode").asString() + "=" + b.get("onHand").asLong())
                .containsExactly("A-01=7", "B-01=4");
    }

    @Test
    void theShapeOfEveryMovementIsChecked() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        stockUp(warehouse.id(), warehouse.binA(), product, 5);
        long adjustment = jdbc.sql("SELECT max(id) FROM stock_adjustments WHERE product_id = :p").param("p", product)
                .query(Long.class).single();

        // An ADJUSTMENT row that claims to change the reservation: refused by stock_movements_shape.
        assertThatThrownBy(() -> insertMovement("ADJUSTMENT", warehouse.id(), warehouse.binA(), product, 1, 1, adjustment))
                .hasStackTraceContaining("stock_movements_shape");
        // An adjustment posted twice: refused by the "once" index.
        assertThatThrownBy(() -> insertMovement("ADJUSTMENT", warehouse.id(), warehouse.binA(), product, 1, 0, adjustment))
                .hasStackTraceContaining("stock_movements_adjustment_once");
    }

    @Test
    void aMovementCannotPointAtABinOfAnotherWarehouse() throws Exception {
        Warehouse first = warehouse();
        Warehouse second = warehouse();
        long product = product();
        // Composite foreign key (warehouse_id, bin_id) -> bin_locations (warehouse_id, id).
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO stock_adjustments (warehouse_id, bin_id, product_id, quantity_delta, reason, created_by, created_at)
                        VALUES (:w, :bin, :p, 1, 'test', 'test', now())""")
                .param("w", first.id()).param("bin", second.binA()).param("p", product).update())
                .hasStackTraceContaining("stock_adjustments_bin");
    }

    @Test
    void theTriggerRefusesNegativeStockWithItsOwnErrorCode() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        stockUp(warehouse.id(), warehouse.binA(), product, 2);

        var response = perform("/api/stock/adjustments", TestUsers.MANAGER, Map.of("warehouseId", warehouse.id(),
                "binId", warehouse.binA(), "productId", product, "quantity", -3, "reason", "Count"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(422);
        JsonNode problem = json.readTree(response.getContentAsString());
        assertThat(problem.get("code").asString()).isEqualTo("insufficient_stock");
        assertThat(problem.get("shortages").get(0).get("available").asLong()).isEqualTo(2);
        // ... and the failed adjustment left nothing behind (its document row was rolled back too).
        assertThat(jdbc.sql("SELECT count(*) FROM stock_adjustments WHERE product_id = :p").param("p", product)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void productsAlwaysHaveTheirBaseUnit() throws Exception {
        long product = product("KG-" + unique("P"), "G", Map.of("KG", 1000L), 900);
        List<Map<String, Object>> units = jdbc.sql("SELECT uom_code, base_units_per_uom FROM product_units WHERE product_id = :p "
                        + "ORDER BY base_units_per_uom")
                .param("p", product).query().listOfRows();
        assertThat(units).extracting(u -> u.get("uom_code") + "=" + u.get("base_units_per_uom")).containsExactly("G=1", "KG=1000");
    }

    private void insertMovement(String type, long warehouseId, long binId, long productId, long onHand, long reserved,
            long adjustmentId) {
        jdbc.sql("""
                        INSERT INTO stock_movements (movement_type, warehouse_id, bin_id, product_id, on_hand_delta, reserved_delta,
                                                     adjustment_id, occurred_at, recorded_by)
                        VALUES (:type, :w, :bin, :p, :onHand, :reserved, :adj, now(), 'test')""")
                .param("type", type).param("w", warehouseId).param("bin", binId).param("p", productId)
                .param("onHand", onHand).param("reserved", reserved).param("adj", adjustmentId)
                .update();
    }
}
