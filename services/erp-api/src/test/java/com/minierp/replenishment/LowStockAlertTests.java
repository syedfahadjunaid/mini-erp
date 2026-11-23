package com.minierp.replenishment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.minierp.IntegrationTest;
import com.minierp.TestUsers;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Reorder points raise low-stock alerts through domain events: the stock ledger publishes
 * StockLevelChanged, the replenishment module listens (after commit, asynchronously) and keeps
 * one alert per warehouse and product in step with the stock.
 */
class LowStockAlertTests extends IntegrationTest {

    @Test
    void anAlertOpensWhenAvailableStockDropsToTheReorderPointAndResolvesWhenRestocked() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        stockUp(warehouse.id(), warehouse.binA(), product, 30);
        put("/api/reorder-rules/" + warehouse.id() + "/" + product, Map.of("reorderPoint", 10, "reorderQuantity", 50));
        assertThat(alerts(warehouse.id(), product)).isEmpty(); // 30 available: fine

        // Reserving 21 leaves 9 available: at or below 10, so an alert opens.
        long order = salesOrder(customer(), warehouse.id(), product, 21).get("id").asLong();
        act("sales-orders", order, "confirm", TestUsers.SALES);
        JsonNode alert = eventually().until(() -> alerts(warehouse.id(), product), list -> !list.isEmpty()).getFirst();
        assertThat(alert.get("status").asString()).isEqualTo("OPEN");
        assertThat(alert.get("availableAtOpen").asLong()).isEqualTo(9);
        assertThat(alert.get("reorderQuantity").asLong()).isEqualTo(50);

        // Further drops don't open a second alert.
        stockUp(warehouse.id(), warehouse.binA(), product, -2);
        long alertId = alert.get("id").asLong();
        JsonNode acknowledged = post("/api/alerts/" + alertId + "/acknowledge", TestUsers.BUYER, Map.of());
        assertThat(acknowledged.get("status").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(acknowledged.get("acknowledgedBy").asString()).isEqualTo("pam");

        // Restocking above the reorder point resolves it.
        stockUp(warehouse.id(), warehouse.binB(), product, 40);
        eventually().until(() -> alerts(warehouse.id(), product).getFirst().get("status").asString().equals("RESOLVED"));
        assertThat(alerts(warehouse.id(), product)).hasSize(1);

        // A later drop opens a NEW alert (the resolved one stays as history).
        stockUp(warehouse.id(), warehouse.binB(), product, -40);
        eventually().until(() -> alerts(warehouse.id(), product).size() == 2);
        assertThat(alerts(warehouse.id(), product).getFirst().get("status").asString()).isEqualTo("OPEN");
    }

    @Test
    void settingARuleWhileStockIsAlreadyLowRaisesTheAlertImmediately() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        stockUp(warehouse.id(), warehouse.binA(), product, 3);

        put("/api/reorder-rules/" + warehouse.id() + "/" + product, Map.of("reorderPoint", 5, "reorderQuantity", 20));
        assertThat(alerts(warehouse.id(), product)).hasSize(1); // synchronous: no event needed

        // Acknowledging twice is a state-machine error.
        long id = alerts(warehouse.id(), product).getFirst().get("id").asLong();
        post("/api/alerts/" + id + "/acknowledge", TestUsers.MANAGER, Map.of());
        perform("/api/alerts/" + id + "/acknowledge", TestUsers.MANAGER, Map.of()).andExpect(status().isConflict());
    }

    @Test
    void manyConcurrentStockChangesStillLeaveExactlyOneAlert() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        stockUp(warehouse.id(), warehouse.binA(), product, 100);
        put("/api/reorder-rules/" + warehouse.id() + "/" + product, Map.of("reorderPoint", 50, "reorderQuantity", 100));

        // 10 orders of 9 each, confirmed one after another: each publishes an event, and the
        // listeners run concurrently on other threads.
        long customer = customer();
        for (int i = 0; i < 10; i++) {
            long order = salesOrder(customer, warehouse.id(), product, 9).get("id").asLong();
            act("sales-orders", order, "confirm", TestUsers.SALES);
        }
        eventually().until(() -> !alerts(warehouse.id(), product).isEmpty());
        Thread.sleep(500); // let any straggling listeners finish
        List<JsonNode> all = alerts(warehouse.id(), product);
        assertThat(all).hasSize(1);
        assertThat(all.getFirst().get("status").asString()).isEqualTo("OPEN");
        assertThat(all.getFirst().get("availableNow").asLong()).isEqualTo(10);
    }

    private void put(String path, Object body) throws Exception {
        performPut(path, TestUsers.MANAGER, body).andExpect(status().isOk());
    }

    private List<JsonNode> alerts(long warehouseId, long productId) throws Exception {
        JsonNode all = get("/api/alerts?warehouseId=" + warehouseId, TestUsers.WAREHOUSE);
        java.util.ArrayList<JsonNode> mine = new java.util.ArrayList<>();
        for (JsonNode alert : all) {
            if (alert.get("productId").asLong() == productId) {
                mine.add(alert);
            }
        }
        return mine;
    }
}
