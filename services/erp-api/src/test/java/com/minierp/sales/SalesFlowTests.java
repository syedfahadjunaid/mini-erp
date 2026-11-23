package com.minierp.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.minierp.IntegrationTest;
import com.minierp.TestUsers;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Sales orders over HTTP: confirming reserves stock, shipping consumes the reservation and
 * picks bins, cancelling or closing releases what's left, and every shipment gets an invoice.
 */
class SalesFlowTests extends IntegrationTest {

    @Test
    void confirmReservesShipConsumesAndTheShipmentIsInvoicedAutomatically() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        stockUp(warehouse.id(), warehouse.binA(), product, 30);
        stockUp(warehouse.id(), warehouse.binB(), product, 20);

        JsonNode order = salesOrder(customer(), warehouse.id(), product, 40);
        long id = order.get("id").asLong();
        assertThat(order.get("allowedActions")).extracting(JsonNode::asString).containsExactlyInAnyOrder("CONFIRM", "CANCEL");
        assertThat(stockLevel(warehouse.id(), product).get("reserved").asLong()).isZero(); // a draft reserves nothing

        JsonNode confirmed = act("sales-orders", id, "confirm", TestUsers.SALES);
        assertThat(confirmed.get("status").asString()).isEqualTo("CONFIRMED");
        JsonNode level = stockLevel(warehouse.id(), product);
        assertThat(level.get("onHand").asLong()).isEqualTo(50);
        assertThat(level.get("reserved").asLong()).isEqualTo(40);
        assertThat(level.get("available").asLong()).isEqualTo(10);

        // Ship 35: picked from A-01 (30) then B-01 (5), in bin-code order.
        long lineId = confirmed.get("lines").get(0).get("id").asLong();
        JsonNode shipment = post("/api/sales-orders/" + id + "/shipments", TestUsers.WAREHOUSE,
                Map.of("lines", List.of(Map.of("lineId", lineId, "quantity", 35))));
        JsonNode picks = shipment.get("lines").get(0).get("picks");
        assertThat(picks).hasSize(2);
        assertThat(picks.get(0).get("binId").asLong()).isEqualTo(warehouse.binA());
        assertThat(picks.get(0).get("quantity").asLong()).isEqualTo(30);
        assertThat(picks.get(1).get("quantity").asLong()).isEqualTo(5);

        level = stockLevel(warehouse.id(), product);
        assertThat(level.get("onHand").asLong()).isEqualTo(15);
        assertThat(level.get("reserved").asLong()).isEqualTo(5);   // 40 reserved - 35 shipped
        assertThat(level.get("available").asLong()).isEqualTo(10); // shipping doesn't change availability
        assertThat(get("/api/sales-orders/" + id, TestUsers.SALES).get("status").asString()).isEqualTo("PARTIALLY_SHIPPED");

        // The invoice is issued by an event listener after the shipment committed.
        long shipmentId = shipment.get("id").asLong();
        JsonNode invoice = eventually().until(() -> invoiceFor(shipmentId), n -> n != null);
        assertThat(invoice.get("invoiceNumber").asString()).matches("INV-\\d{4}-\\d{6}");
        assertThat(invoice.get("netTotal").get("amountMinor").asLong()).isEqualTo(35 * 1000);
        assertThat(invoice.get("taxTotal").get("amountMinor").asLong()).isEqualTo(7000);   // 20 %
        assertThat(invoice.get("grossTotal").get("amountMinor").asLong()).isEqualTo(42_000);
        assertThat(invoice.get("issuedBy").asString()).isEqualTo("system");

        // Asking again (say, a finance clerk who didn't see it) returns the same invoice: 200, not 201.
        JsonNode again = read(perform("/api/shipments/" + shipmentId + "/invoice", TestUsers.FINANCE, Map.of())
                .andExpect(status().isOk()));
        assertThat(again.get("id").asLong()).isEqualTo(invoice.get("id").asLong());

        // Close short: the last 5 won't ship, so their reservation is released.
        assertThat(act("sales-orders", id, "close", TestUsers.SALES).get("status").asString()).isEqualTo("CLOSED");
        level = stockLevel(warehouse.id(), product);
        assertThat(level.get("reserved").asLong()).isZero();
        assertThat(level.get("available").asLong()).isEqualTo(15);
    }

    @Test
    void confirmIsAllOrNothingAndNamesEveryShortLine() throws Exception {
        Warehouse warehouse = warehouse();
        long plenty = product();
        long scarce = product();
        long none = product();
        stockUp(warehouse.id(), warehouse.binA(), plenty, 100);
        stockUp(warehouse.id(), warehouse.binA(), scarce, 3);

        long id = salesOrder(customer(), warehouse.id(), plenty, 10, scarce, 5, none, 2).get("id").asLong();
        perform("/api/sales-orders/" + id + "/actions/confirm", TestUsers.SALES, Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("insufficient_stock"))
                .andExpect(jsonPath("$.shortages.length()").value(2))
                .andExpect(jsonPath("$.shortages[0].available").value(3))
                .andExpect(jsonPath("$.shortages[0].requested").value(5));

        // Nothing was reserved, not even the line that fitted.
        assertThat(stockLevel(warehouse.id(), plenty).get("reserved").asLong()).isZero();
        assertThat(get("/api/sales-orders/" + id, TestUsers.SALES).get("status").asString()).isEqualTo("DRAFT");
    }

    @Test
    void cancellingAConfirmedOrderReleasesItsReservations() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        stockUp(warehouse.id(), warehouse.binA(), product, 10);
        long id = salesOrder(customer(), warehouse.id(), product, 7).get("id").asLong();
        act("sales-orders", id, "confirm", TestUsers.SALES);
        assertThat(stockLevel(warehouse.id(), product).get("available").asLong()).isEqualTo(3);

        act("sales-orders", id, "cancel", TestUsers.SALES);
        assertThat(stockLevel(warehouse.id(), product).get("available").asLong()).isEqualTo(10);

        JsonNode movements = get("/api/stock/movements?warehouseId=" + warehouse.id() + "&productId=" + product, TestUsers.MANAGER);
        assertThat(movements).extracting(m -> m.get("type").asString()).containsExactly("RELEASE", "RESERVE", "ADJUSTMENT");
    }

    @Test
    void shipmentsAreCheckedAgainstTheOrder() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        stockUp(warehouse.id(), warehouse.binA(), product, 24);
        // 1 BOX = 12 EA; order in boxes, ship in boxes.
        JsonNode order = post("/api/sales-orders", TestUsers.SALES, Map.of("customerId", customer(), "warehouseId", warehouse.id(),
                "lines", List.of(Map.of("productId", product, "uom", "BOX", "quantity", 1.5, "unitPriceMinor", 12_000))));
        long id = order.get("id").asLong();
        long lineId = order.get("lines").get(0).get("id").asLong();
        assertThat(order.get("lines").get(0).get("baseQuantity").asLong()).isEqualTo(18);
        assertThat(order.get("netTotal").get("amountMinor").asLong()).isEqualTo(18_000);

        // Can't ship a draft.
        perform("/api/sales-orders/" + id + "/shipments", TestUsers.WAREHOUSE,
                Map.of("lines", List.of(Map.of("lineId", lineId, "quantity", 1))))
                .andExpect(status().isConflict());
        act("sales-orders", id, "confirm", TestUsers.SALES);

        // Can't ship more than ordered, can't ship as SALES.
        perform("/api/sales-orders/" + id + "/shipments", TestUsers.WAREHOUSE,
                Map.of("lines", List.of(Map.of("lineId", lineId, "quantity", 2))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("over_shipment"));
        perform("/api/sales-orders/" + id + "/shipments", TestUsers.SALES,
                Map.of("lines", List.of(Map.of("lineId", lineId, "quantity", 1))))
                .andExpect(status().isForbidden());

        post("/api/sales-orders/" + id + "/shipments", TestUsers.WAREHOUSE,
                Map.of("lines", List.of(Map.of("lineId", lineId, "quantity", 1.5))));
        assertThat(get("/api/sales-orders/" + id, TestUsers.SALES).get("status").asString()).isEqualTo("SHIPPED");
        perform("/api/sales-orders/" + id + "/actions/cancel", TestUsers.SALES, Map.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invalid_transition"));
    }

    private JsonNode invoiceFor(long shipmentId) throws Exception {
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/shipments/" + shipmentId + "/invoice").with(TestUsers.FINANCE.bearer())).andReturn();
        return result.getResponse().getStatus() == 200 ? json.readTree(result.getResponse().getContentAsString()) : null;
    }
}
