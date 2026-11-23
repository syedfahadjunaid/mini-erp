package com.minierp.purchasing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.minierp.IntegrationTest;
import com.minierp.TestUsers;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Purchase orders end to end over HTTP: lifecycle, roles, separation of duties, receipts into stock. */
class PurchasingApiTests extends IntegrationTest {

    private JsonNode draftOrder(long supplierId, long warehouseId, long productId) throws Exception {
        return post("/api/purchase-orders", TestUsers.BUYER, Map.of(
                "supplierId", supplierId,
                "warehouseId", warehouseId,
                "lines", List.of(Map.of("productId", productId, "uom", "BOX", "quantity", 10, "unitPriceMinor", 4999))));
    }

    @Test
    void fullLifecycleFromDraftToReceivedPutsStockIntoTheWarehouse() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        JsonNode order = draftOrder(supplier(), warehouse.id(), product);
        long id = order.get("id").asLong();

        assertThat(order.get("status").asString()).isEqualTo("DRAFT");
        assertThat(order.get("currency").asString()).isEqualTo("EUR");             // the supplier's
        assertThat(order.get("lines").get(0).get("baseQuantity").asLong()).isEqualTo(120); // 10 BOX of 12
        assertThat(order.get("total").get("amountMinor").asLong()).isEqualTo(49_990);
        assertThat(order.get("orderNumber").asString()).startsWith("PO-");

        act("purchase-orders", id, "submit", TestUsers.BUYER);
        JsonNode approved = act("purchase-orders", id, "approve", TestUsers.MANAGER);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("approvedBy").asString()).isEqualTo("max");

        long lineId = approved.get("lines").get(0).get("id").asLong();
        JsonNode receipt = post("/api/purchase-orders/" + id + "/receipts", TestUsers.WAREHOUSE,
                Map.of("lines", List.of(Map.of("lineId", lineId, "quantity", 4, "binId", warehouse.binA()))));
        assertThat(receipt.get("orderStatus").asString()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(receipt.get("lines").get(0).get("baseQuantity").asLong()).isEqualTo(48);
        assertThat(stockLevel(warehouse.id(), product).get("onHand").asLong()).isEqualTo(48);

        // The rest, without a bin: it lands in the warehouse's RECEIVING bin.
        receipt = post("/api/purchase-orders/" + id + "/receipts", TestUsers.WAREHOUSE,
                Map.of("lines", List.of(Map.of("lineId", lineId, "quantity", 6))));
        assertThat(receipt.get("orderStatus").asString()).isEqualTo("RECEIVED");
        assertThat(receipt.get("lines").get(0).get("binId").asLong()).isEqualTo(warehouse.receivingBin());

        JsonNode level = stockLevel(warehouse.id(), product);
        assertThat(level.get("onHand").asLong()).isEqualTo(120);
        assertThat(level.get("available").asLong()).isEqualTo(120);

        JsonNode finalOrder = get("/api/purchase-orders/" + id, TestUsers.BUYER);
        assertThat(finalOrder.get("history")).extracting(h -> h.get("action").asString())
                .containsExactly("CREATE", "SUBMIT", "APPROVE", "RECEIVE", "RECEIVE");
        assertThat(finalOrder.get("allowedActions")).isEmpty();
    }

    @Test
    void nobodyApprovesTheirOwnOrder() throws Exception {
        long id = draftOrderAs(TestUsers.BUYING_MANAGER);
        act("purchase-orders", id, "submit", TestUsers.BUYING_MANAGER);

        JsonNode asCreator = get("/api/purchase-orders/" + id, TestUsers.BUYING_MANAGER);
        assertThat(asCreator.get("allowedActions")).extracting(JsonNode::asString).doesNotContain("APPROVE", "REJECT");

        perform("/api/purchase-orders/" + id + "/actions/approve", TestUsers.BUYING_MANAGER, Map.of())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("separation_of_duties"));

        JsonNode asManager = get("/api/purchase-orders/" + id, TestUsers.MANAGER);
        assertThat(asManager.get("allowedActions")).extracting(JsonNode::asString).contains("APPROVE", "REJECT");
        assertThat(act("purchase-orders", id, "approve", TestUsers.MANAGER).get("status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void theDatabaseAlsoRefusesSelfApproval() throws Exception {
        long id = draftOrderAs(TestUsers.BUYER);
        // Even if the Java check were bypassed, the CHECK constraint holds.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(
                        "UPDATE purchase_orders SET approved_by = created_by, approved_at = now() WHERE id = :id")
                        .param("id", id).update())
                .hasStackTraceContaining("purchase_orders_approver_is_not_creator");
    }

    @Test
    void rolesAreEnforced() throws Exception {
        Warehouse warehouse = warehouse();
        long supplier = supplier();
        long product = product();
        perform("/api/purchase-orders", TestUsers.WAREHOUSE, Map.of("supplierId", supplier, "warehouseId", warehouse.id(),
                "lines", List.of(Map.of("productId", product, "quantity", 1, "unitPriceMinor", 1))))
                .andExpect(status().isForbidden());

        long id = draftOrderAs(TestUsers.BUYER);
        act("purchase-orders", id, "submit", TestUsers.BUYER);
        perform("/api/purchase-orders/" + id + "/actions/approve", TestUsers.OTHER_BUYER, Map.of())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("missing_role"));
    }

    @Test
    void illegalTransitionsAndOverReceiptsAreRefused() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        JsonNode order = draftOrder(supplier(), warehouse.id(), product);
        long id = order.get("id").asLong();
        long lineId = order.get("lines").get(0).get("id").asLong();

        // Can't receive against a draft, can't approve a draft.
        perform("/api/purchase-orders/" + id + "/receipts", TestUsers.WAREHOUSE,
                Map.of("lines", List.of(Map.of("lineId", lineId, "quantity", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invalid_transition"));
        perform("/api/purchase-orders/" + id + "/actions/approve", TestUsers.MANAGER, Map.of())
                .andExpect(status().isConflict());

        act("purchase-orders", id, "submit", TestUsers.BUYER);
        act("purchase-orders", id, "approve", TestUsers.MANAGER);

        // 11 boxes on a 10-box line: 422 naming the field.
        perform("/api/purchase-orders/" + id + "/receipts", TestUsers.WAREHOUSE,
                Map.of("lines", List.of(Map.of("lineId", lineId, "quantity", 11))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("over_receipt"))
                .andExpect(jsonPath("$.errors[0].field").value("lines[0].quantity"));

        // Half a box is 6 EA: fine. A twelfth of an EA is not.
        post("/api/purchase-orders/" + id + "/receipts", TestUsers.WAREHOUSE,
                Map.of("lines", List.of(Map.of("lineId", lineId, "quantity", 0.5))));
        perform("/api/purchase-orders/" + id + "/receipts", TestUsers.WAREHOUSE,
                Map.of("lines", List.of(Map.of("lineId", lineId, "quantity", 0.001))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("fractional_base_quantity"));

        // Close short: no more deliveries expected.
        assertThat(act("purchase-orders", id, "close", TestUsers.BUYER).get("status").asString()).isEqualTo("CLOSED");
        assertThat(stockLevel(warehouse.id(), product).get("onHand").asLong()).isEqualTo(6);
    }

    @Test
    void draftsCanBeEditedWithOptimisticLocking() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        JsonNode order = draftOrder(supplier(), warehouse.id(), product);
        long id = order.get("id").asLong();
        long version = order.get("version").asLong();

        Map<String, Object> edit = Map.of("version", version, "notes", "Rush order",
                "lines", List.of(Map.of("productId", product, "quantity", 5, "unitPriceMinor", 100),
                        Map.of("productId", product, "uom", "BOX", "quantity", 1, "unitPriceMinor", 1100)));
        JsonNode edited = read(performPut("/api/purchase-orders/" + id, TestUsers.BUYER, edit).andExpect(status().isOk()));
        assertThat(edited.get("lines")).hasSize(2);
        assertThat(edited.get("total").get("amountMinor").asLong()).isEqualTo(500 + 1100);

        // Someone editing the old version loses: 409, nothing overwritten.
        performPut("/api/purchase-orders/" + id, TestUsers.BUYER, edit)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stale_version"));

        act("purchase-orders", id, "submit", TestUsers.BUYER);
        performPut("/api/purchase-orders/" + id, TestUsers.BUYER,
                Map.of("version", edited.get("version").asLong() + 1, "lines", edit.get("lines")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_editable"));
    }

    @Test
    void validatesInput() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        perform("/api/purchase-orders", TestUsers.BUYER, Map.of("supplierId", supplier(), "warehouseId", warehouse.id(),
                "lines", List.of(Map.of("productId", product, "uom", "PAL", "quantity", 1, "unitPriceMinor", 1))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("unknown_unit"))
                .andExpect(jsonPath("$.errors[0].field").value("lines[0].quantity"));
        perform("/api/purchase-orders", TestUsers.BUYER, Map.of("supplierId", supplier(), "warehouseId", warehouse.id(),
                "lines", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lines"));
    }

    private long draftOrderAs(TestUsers.User buyer) throws Exception {
        Warehouse warehouse = warehouse();
        return post("/api/purchase-orders", buyer, Map.of("supplierId", supplier(), "warehouseId", warehouse.id(),
                "lines", List.of(Map.of("productId", product(), "quantity", 3, "unitPriceMinor", 100)))).get("id").asLong();
    }
}
