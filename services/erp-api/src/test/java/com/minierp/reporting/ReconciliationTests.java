package com.minierp.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.minierp.IntegrationTest;
import com.minierp.TestUsers;
import com.minierp.inventory.TransferService;
import com.minierp.inventory.TransferDtos;
import com.minierp.sales.SalesDtos;
import com.minierp.sales.SalesOrderAction;
import com.minierp.sales.SalesOrderService;
import com.minierp.sales.ShippingService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * The ledger always reconciles: after a burst of random concurrent operations (confirm, ship,
 * cancel, transfer, adjust), every balance equals the sum of its movements, every running total
 * is right, and every order's reservation matches what it still needs. And the check really
 * catches a broken balance.
 */
class ReconciliationTests extends IntegrationTest {

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    SalesOrderService salesOrders;

    @Autowired
    ShippingService shipping;

    @Autowired
    TransferService transfers;

    @Autowired
    StockReportService stockReports;

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    void randomConcurrentOperationsAlwaysReconcile() throws Exception {
        Warehouse main = warehouse();
        Warehouse east = warehouse();
        long[] products = {product(), product(), product()};
        for (long product : products) {
            stockUp(main.id(), main.binA(), product, 40);
            stockUp(main.id(), main.binB(), product, 20);
        }
        long customer = customer();
        Random random = new Random(42); // fixed seed: the same "random" scenario every run

        // Draft orders to work with.
        List<JsonNode> orders = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            orders.add(salesOrder(customer, main.id(), products[random.nextInt(3)], 1 + random.nextInt(8),
                    products[random.nextInt(3)], 1 + random.nextInt(8)));
        }

        // Round 1: confirm them all at once (some will be short of stock), plus transfers and adjustments.
        List<Callable<Object>> round1 = new ArrayList<>();
        for (JsonNode order : orders) {
            long id = order.get("id").asLong();
            round1.add(() -> salesOrders.act(id, SalesOrderAction.CONFIRM, null, TestUsers.SALES.actor()));
        }
        for (int i = 0; i < 6; i++) {
            long product = products[random.nextInt(3)];
            round1.add(() -> transfers.create(new TransferDtos.CreateTransferRequest(main.id(), east.id(),
                    List.of(new TransferDtos.LineRequest(product, main.binB(), BigDecimal.valueOf(3), null))),
                    TestUsers.WAREHOUSE.actor()));
        }
        race(round1);

        // Round 2: ship, cancel or close whatever got confirmed, all at once.
        List<Callable<Object>> round2 = new ArrayList<>();
        for (JsonNode order : orders) {
            long id = order.get("id").asLong();
            SalesDtos.SalesOrderResponse current = salesOrders.get(id, TestUsers.SALES.actor());
            if (!current.status().name().equals("CONFIRMED")) {
                continue;
            }
            switch (random.nextInt(3)) {
                case 0 -> round2.add(() -> salesOrders.act(id, SalesOrderAction.CANCEL, null, TestUsers.SALES.actor()));
                case 1 -> round2.add(() -> shipping.ship(id, new SalesDtos.ShipmentRequest(current.lines().stream()
                        .map(l -> new SalesDtos.ShipmentLineRequest(l.id(), l.quantity())).toList()), TestUsers.WAREHOUSE.actor()));
                default -> round2.add(() -> shipping.ship(id, new SalesDtos.ShipmentRequest(List.of(
                        new SalesDtos.ShipmentLineRequest(current.lines().getFirst().id(), BigDecimal.ONE))),
                        TestUsers.WAREHOUSE.actor()));
            }
        }
        race(round2);

        ReconciliationService.Report report = reconciliation.run();
        assertThat(report.discrepancies()).isEmpty();
        assertThat(report.balanced()).isTrue();
        assertThat(report.movementsChecked()).isPositive();

        JsonNode overHttp = get("/api/reports/reconciliation", TestUsers.FINANCE);
        assertThat(overHttp.get("balanced").asBoolean()).isTrue();
    }

    @Test
    void theCheckCatchesABalanceThatDisagreesWithTheLedger() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        stockUp(warehouse.id(), warehouse.binA(), product, 10);
        assertThat(reconciliation.run().balanced()).isTrue();

        // Simulate corruption with the superuser (the app login couldn't do this).
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            admin.createStatement().executeUpdate("UPDATE erp.stock_levels SET on_hand = on_hand + 5 WHERE product_id = " + product);
            try {
                ReconciliationService.Report report = reconciliation.run();
                assertThat(report.balanced()).isFalse();
                assertThat(report.discrepancies()).extracting(ReconciliationService.Discrepancy::check)
                        .contains("LEVEL_ON_HAND", "BINS_VS_LEVEL");
                assertThat(report.discrepancies()).filteredOn(d -> d.check().equals("LEVEL_ON_HAND"))
                        .singleElement()
                        .satisfies(d -> {
                            assertThat(d.productId()).isEqualTo(product);
                            assertThat(d.expected()).isEqualTo(10);
                            assertThat(d.actual()).isEqualTo(15);
                        });
            } finally {
                admin.createStatement().executeUpdate("UPDATE erp.stock_levels SET on_hand = on_hand - 5 WHERE product_id = " + product);
            }
        }
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void stockCanBeRebuiltForAPastMomentFromTheLedgerAlone() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        clock.set(Instant.parse("2030-03-01T09:00:00Z"));
        stockUp(warehouse.id(), warehouse.binA(), product, 10);
        clock.set(Instant.parse("2030-03-02T09:00:00Z"));
        stockUp(warehouse.id(), warehouse.binA(), product, 5);
        clock.set(Instant.parse("2030-03-03T09:00:00Z"));
        stockUp(warehouse.id(), warehouse.binA(), product, -12);

        Map<String, Long> expected = Map.of(
                "2030-03-01T12:00:00Z", 10L,
                "2030-03-02T12:00:00Z", 15L,
                "2030-03-03T12:00:00Z", 3L);
        for (var entry : expected.entrySet()) {
            StockReport report = stockReports.build(warehouse.id(), Instant.parse(entry.getKey()));
            assertThat(report.rows()).singleElement()
                    .satisfies(row -> assertThat(row.onHand()).as("on hand as of %s", entry.getKey()).isEqualTo(entry.getValue()));
        }
        // ... and "now" rebuilt from the ledger equals the stored balance.
        assertThat(stockReports.build(warehouse.id(), Instant.parse("2099-01-01T00:00:00Z")).rows().getFirst().onHand())
                .isEqualTo(stockReports.build(warehouse.id(), null).rows().getFirst().onHand());
    }
}
