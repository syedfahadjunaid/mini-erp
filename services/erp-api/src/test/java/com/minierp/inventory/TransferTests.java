package com.minierp.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.minierp.IntegrationTest;
import com.minierp.TestUsers;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Inter-warehouse transfers: dispatch, in transit, receive; reserved stock stays put. */
class TransferTests extends IntegrationTest {

    @Test
    void stockLeavesTheSourceOnDispatchAndArrivesOnReceipt() throws Exception {
        Warehouse main = warehouse();
        Warehouse east = warehouse();
        long product = product();
        stockUp(main.id(), main.binA(), product, 50);

        JsonNode transfer = post("/api/transfers", TestUsers.WAREHOUSE, Map.of("fromWarehouseId", main.id(),
                "toWarehouseId", east.id(),
                "lines", List.of(Map.of("productId", product, "fromBinId", main.binA(), "uom", "BOX", "quantity", 2))));
        assertThat(transfer.get("status").asString()).isEqualTo("IN_TRANSIT");
        assertThat(transfer.get("lines").get(0).get("quantity").asLong()).isEqualTo(24);
        assertThat(stockLevel(main.id(), product).get("onHand").asLong()).isEqualTo(26);
        assertThat(stockLevel(east.id(), product).get("onHand").asLong()).isZero(); // on the truck

        long id = transfer.get("id").asLong();
        JsonNode received = post("/api/transfers/" + id + "/receive", TestUsers.WAREHOUSE, Map.of("toBinId", east.binB()));
        assertThat(received.get("status").asString()).isEqualTo("RECEIVED");
        assertThat(received.get("lines").get(0).get("toBinId").asLong()).isEqualTo(east.binB());
        assertThat(stockLevel(east.id(), product).get("onHand").asLong()).isEqualTo(24);

        // Received twice? No: 409, and the stock isn't booked twice.
        perform("/api/transfers/" + id + "/receive", TestUsers.WAREHOUSE, Map.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invalid_transition"));
        assertThat(stockLevel(east.id(), product).get("onHand").asLong()).isEqualTo(24);
    }

    @Test
    void reservedStockCannotBeTransferredAway() throws Exception {
        Warehouse main = warehouse();
        Warehouse east = warehouse();
        long product = product();
        stockUp(main.id(), main.binA(), product, 10);
        long order = salesOrder(customer(), main.id(), product, 8).get("id").asLong();
        act("sales-orders", order, "confirm", TestUsers.SALES);

        perform("/api/transfers", TestUsers.WAREHOUSE, Map.of("fromWarehouseId", main.id(), "toWarehouseId", east.id(),
                "lines", List.of(Map.of("productId", product, "fromBinId", main.binA(), "quantity", 5))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("insufficient_stock"))
                .andExpect(jsonPath("$.shortages[0].available").value(2))
                .andExpect(jsonPath("$.shortages[0].requested").value(5));

        // Nothing moved: the failed transfer was rolled back completely (no transfer row either).
        assertThat(stockLevel(main.id(), product).get("onHand").asLong()).isEqualTo(10);
        post("/api/transfers", TestUsers.WAREHOUSE, Map.of("fromWarehouseId", main.id(), "toWarehouseId", east.id(),
                "lines", List.of(Map.of("productId", product, "fromBinId", main.binA(), "quantity", 2))));
        assertThat(stockLevel(main.id(), product).get("available").asLong()).isZero();
    }

    @Test
    void aBinCannotGiveMoreThanItHolds() throws Exception {
        Warehouse main = warehouse();
        Warehouse east = warehouse();
        long product = product();
        stockUp(main.id(), main.binA(), product, 5);
        stockUp(main.id(), main.binB(), product, 5);

        perform("/api/transfers", TestUsers.WAREHOUSE, Map.of("fromWarehouseId", main.id(), "toWarehouseId", east.id(),
                "lines", List.of(Map.of("productId", product, "fromBinId", main.binA(), "quantity", 7))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("insufficient_bin_stock"));
    }

    @Test
    void transfersNeedTwoWarehousesAndTheRightRole() throws Exception {
        Warehouse main = warehouse();
        long product = product();
        perform("/api/transfers", TestUsers.WAREHOUSE, Map.of("fromWarehouseId", main.id(), "toWarehouseId", main.id(),
                "lines", List.of(Map.of("productId", product, "fromBinId", main.binA(), "quantity", 1))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("same_warehouse"));
        perform("/api/transfers", TestUsers.SALES, Map.of("fromWarehouseId", main.id(), "toWarehouseId", main.id(),
                "lines", List.of(Map.of("productId", product, "fromBinId", main.binA(), "quantity", 1))))
                .andExpect(status().isForbidden());
    }
}
