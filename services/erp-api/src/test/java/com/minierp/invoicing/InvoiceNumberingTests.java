package com.minierp.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minierp.IntegrationTest;
import com.minierp.TestUsers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Gap-free invoice numbers per fiscal year. Each test moves the clock into a year of its own,
 * so it sees that year's counter start at 1 regardless of what other tests invoiced.
 * (The business time zone in tests is Pacific/Auckland: noon UTC on 1 July is still 1 July.)
 */
class InvoiceNumberingTests extends IntegrationTest {

    @Autowired
    InvoiceService invoices;

    @Autowired
    InvoiceNumbering numbering;

    @Autowired
    TransactionTemplate transactions;

    /**
     * Invoices are issued asynchronously after each shipment. Wait until other tests' shipments
     * are all invoiced, so none of them lands in this test's fiscal year once the clock moves.
     */
    @BeforeEach
    void earlierShipmentsAreInvoiced() {
        eventually().until(() -> jdbc.sql("""
                        SELECT count(*) FROM shipments s
                         WHERE NOT EXISTS (SELECT 1 FROM invoices i WHERE i.shipment_id = s.id)""")
                .query(Long.class).single() == 0);
    }

    @Test
    void concurrentInvoicesGetConsecutiveNumbersWithoutGapsOrDuplicates() throws Exception {
        clock.set(Instant.parse("2041-07-01T12:00:00Z"));
        List<Long> shipments = shipments(12);

        List<Callable<Object>> issues = new ArrayList<>();
        for (long shipment : shipments) {
            issues.add(() -> invoices.issueForShipment(shipment, TestUsers.FINANCE.actor()));
        }
        race(issues);

        // The event listener may have issued some of them too: either way, one invoice per shipment.
        eventually().until(() -> invoiceCount(2041) == 12);
        List<Integer> numbers = jdbc.sql("SELECT sequence_number FROM invoices WHERE fiscal_year = 2041 ORDER BY 1")
                .query(Integer.class).list();
        assertThat(numbers).containsExactlyElementsOf(IntStream.rangeClosed(1, 12).boxed().toList());
        assertThat(jdbc.sql("SELECT invoice_number FROM invoices WHERE fiscal_year = 2041 ORDER BY 1").query(String.class).list())
                .first().isEqualTo("INV-2041-000001");
    }

    @Test
    void aRolledBackInvoiceLeavesNoGap() throws Exception {
        clock.set(Instant.parse("2042-07-01T12:00:00Z"));
        List<Long> shipments = shipments(1);
        eventually().until(() -> invoiceCount(2042) == 1); // INV-2042-000001, by the listener

        // A transaction draws number 2 and then fails before the invoice is written.
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            numbering.lockYear(2042);
            assertThat(numbering.next(2042)).isEqualTo(2);
            throw new IllegalStateException("simulated failure after drawing a number");
        })).hasMessageContaining("simulated failure");

        // The increment was rolled back with it: the next real invoice is number 2, not 3.
        long another = shipments(1).getFirst();
        eventually().until(() -> invoiceCount(2042) == 2);
        assertThat(invoices.forShipment(another).orElseThrow().invoiceNumber()).isEqualTo("INV-2042-000002");
        assertThat(invoices.forShipment(shipments.getFirst()).orElseThrow().invoiceNumber()).isEqualTo("INV-2042-000001");
    }

    @Test
    void theDatabaseRefusesANumberDrawnButNotUsed() {
        // Drawing a number and committing without an invoice is caught at COMMIT by a deferred trigger.
        int before = counter(2043);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            numbering.lockYear(2043);
            numbering.next(2043);
        })).hasStackTraceContaining("was drawn but no invoice uses it");
        assertThat(counter(2043)).isEqualTo(before);

        // And the counter can only move by one.
        transactions.executeWithoutResult(tx -> numbering.lockYear(2044));
        assertThatThrownBy(() -> jdbc.sql("UPDATE invoice_number_counters SET last_number = last_number + 2 WHERE fiscal_year = 2044")
                .update())
                .hasStackTraceContaining("may only be incremented by one");
    }

    @Test
    void theFiscalYearIsTheIssueDateInTheCompanysTimeZone() throws Exception {
        // 31 Dec 2045, 12:00 UTC is already 1 Jan 2046, 01:00 in Auckland (UTC+13 in summer).
        clock.set(Instant.parse("2045-12-31T12:00:00Z"));
        long shipment = shipments(1).getFirst();
        eventually().until(() -> invoices.forShipment(shipment).isPresent());

        InvoiceDtos.InvoiceResponse invoice = invoices.forShipment(shipment).orElseThrow();
        assertThat(invoice.issueDate()).hasToString("2046-01-01");
        assertThat(invoice.fiscalYear()).isEqualTo(2046);
        assertThat(invoice.invoiceNumber()).isEqualTo("INV-2046-000001");
        assertThat(invoice.dueDate()).hasToString("2046-01-15"); // the customer's 14 days
    }

    @Test
    void invoicesNeverChange() throws Exception {
        clock.set(Instant.parse("2047-07-01T12:00:00Z"));
        long shipment = shipments(1).getFirst();
        eventually().until(() -> invoices.forShipment(shipment).isPresent());
        long id = invoices.forShipment(shipment).orElseThrow().id();

        assertThatThrownBy(() -> jdbc.sql("UPDATE invoices SET net_total_minor = 0 WHERE id = :id").param("id", id).update())
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM invoices WHERE id = :id").param("id", id).update())
                .hasStackTraceContaining("permission denied");
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** {@code count} shipped sales orders (one line each); returns the shipment ids. */
    private List<Long> shipments(int count) throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        stockUp(warehouse.id(), warehouse.binA(), product, count);
        long customer = customer();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            JsonNode order = salesOrder(customer, warehouse.id(), product, 1);
            long orderId = order.get("id").asLong();
            act("sales-orders", orderId, "confirm", TestUsers.SALES);
            long lineId = order.get("lines").get(0).get("id").asLong();
            ids.add(post("/api/sales-orders/" + orderId + "/shipments", TestUsers.WAREHOUSE,
                    Map.of("lines", List.of(Map.of("lineId", lineId, "quantity", 1)))).get("id").asLong());
        }
        return ids;
    }

    private long invoiceCount(int year) {
        return jdbc.sql("SELECT count(*) FROM invoices WHERE fiscal_year = :y").param("y", year).query(Long.class).single();
    }

    private int counter(int year) {
        return jdbc.sql("SELECT COALESCE(max(last_number), 0) FROM invoice_number_counters WHERE fiscal_year = :y")
                .param("y", year).query(Integer.class).single();
    }
}
