package com.minierp.invoicing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gap-free invoice numbers: one counter row per fiscal year, incremented under a row lock in
 * the same transaction that inserts the invoice. See V4__invoicing.sql for why a sequence
 * won't do and for the triggers that guard it.
 *
 * <p>Usage, always in this order and in one transaction:
 * <pre>
 *   numbering.lockYear(2026);          // waits for anyone else invoicing in 2026
 *   ... check the shipment isn't invoiced yet ...
 *   int n = numbering.next(2026);       // draw the number
 *   ... insert the invoice with number n ...
 * </pre>
 * If anything fails after {@code next}, the transaction rolls back and so does the increment.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class InvoiceNumbering {

    private final JdbcClient jdbc;

    InvoiceNumbering(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the year's counter if needed, then locks it until the transaction ends. */
    void lockYear(int fiscalYear) {
        jdbc.sql("INSERT INTO invoice_number_counters (fiscal_year, last_number) VALUES (:year, 0) ON CONFLICT DO NOTHING")
                .param("year", fiscalYear)
                .update();
        jdbc.sql("SELECT last_number FROM invoice_number_counters WHERE fiscal_year = :year FOR UPDATE")
                .param("year", fiscalYear)
                .query(Integer.class)
                .single();
    }

    /** The next number of the (locked) year. */
    int next(int fiscalYear) {
        return jdbc.sql("UPDATE invoice_number_counters SET last_number = last_number + 1 WHERE fiscal_year = :year "
                        + "RETURNING last_number")
                .param("year", fiscalYear)
                .query(Integer.class)
                .single();
    }
}
