package com.minierp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.minierp.IntegrationTest;
import com.minierp.TestUsers;
import com.minierp.reporting.ReconciliationService;
import com.minierp.shared.InsufficientStockException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The race the stock ledger exists to win: many orders confirmed at the same instant, competing
 * for the same few units. The services are called directly from parallel threads (each call is
 * its own transaction, exactly like parallel HTTP requests), released together by a latch.
 */
class ReservationConcurrencyTests extends IntegrationTest {

    @Autowired
    SalesOrderService salesOrders;

    @Autowired
    ReconciliationService reconciliation;

    @Test
    void twentyOrdersRaceForTheLastUnitAndExactlyOneGetsIt() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        stockUp(warehouse.id(), warehouse.binA(), product, 1);
        long customer = customer();

        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            orderIds.add(salesOrder(customer, warehouse.id(), product, 1).get("id").asLong());
        }
        List<Callable<Object>> confirmations = new ArrayList<>();
        for (long id : orderIds) {
            confirmations.add(() -> salesOrders.act(id, SalesOrderAction.CONFIRM, null, TestUsers.SALES.actor()));
        }

        List<Object> outcomes = race(confirmations);

        long confirmed = outcomes.stream().filter(SalesDtos.SalesOrderResponse.class::isInstance).count();
        long refused = outcomes.stream().filter(InsufficientStockException.class::isInstance).count();
        assertThat(confirmed).as("orders that got the unit").isEqualTo(1);
        assertThat(refused).as("orders refused with 'insufficient stock'").isEqualTo(19);
        assertThat(stockLevel(warehouse.id(), product).get("reserved").asLong()).isEqualTo(1);
        assertThat(stockLevel(warehouse.id(), product).get("available").asLong()).isZero();
        assertThat(reconciliation.run().discrepancies()).isEmpty();
    }

    @Test
    void tenUnitsGoToExactlyTenOfTwentyFiveOrders() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        stockUp(warehouse.id(), warehouse.binA(), product, 10);
        long customer = customer();

        List<Callable<Object>> confirmations = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            long id = salesOrder(customer, warehouse.id(), product, 1).get("id").asLong();
            confirmations.add(() -> salesOrders.act(id, SalesOrderAction.CONFIRM, null, TestUsers.SALES.actor()));
        }

        List<Object> outcomes = race(confirmations);

        assertThat(outcomes.stream().filter(SalesDtos.SalesOrderResponse.class::isInstance).count()).isEqualTo(10);
        assertThat(outcomes).allMatch(o -> o instanceof SalesDtos.SalesOrderResponse || o instanceof InsufficientStockException,
                "every outcome is a confirmation or a clean 'insufficient stock' (no deadlocks, no 500s)");
        assertThat(stockLevel(warehouse.id(), product).get("reserved").asLong()).isEqualTo(10);
        assertThat(reconciliation.run().discrepancies()).isEmpty();
    }

    @Test
    void ordersLockingTwoProductsInOppositeOrderDoNotDeadlock() throws Exception {
        // Order X lists (A, B), order Y lists (B, A). Without a fixed lock order they could each
        // hold one balance row and wait for the other forever. StockLedger sorts movements first.
        Warehouse warehouse = warehouse();
        long a = product();
        long b = product();
        stockUp(warehouse.id(), warehouse.binA(), a, 1000);
        stockUp(warehouse.id(), warehouse.binA(), b, 1000);
        long customer = customer();

        List<Callable<Object>> confirmations = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long id = (i % 2 == 0)
                    ? salesOrder(customer, warehouse.id(), a, 1, b, 1).get("id").asLong()
                    : salesOrder(customer, warehouse.id(), b, 1, a, 1).get("id").asLong();
            confirmations.add(() -> salesOrders.act(id, SalesOrderAction.CONFIRM, null, TestUsers.SALES.actor()));
        }

        List<Object> outcomes = race(confirmations);

        assertThat(outcomes).allMatch(SalesDtos.SalesOrderResponse.class::isInstance);
        assertThat(stockLevel(warehouse.id(), a).get("reserved").asLong()).isEqualTo(10);
        assertThat(stockLevel(warehouse.id(), b).get("reserved").asLong()).isEqualTo(10);
    }
}
