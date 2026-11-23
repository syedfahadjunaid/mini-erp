package com.minierp.shared;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Human-readable document numbers such as {@code PO-000042}, drawn from database sequences.
 *
 * <p>Sequences are fast and never block, but {@code nextval} is not rolled back with the
 * transaction, so an order that fails to save leaves a gap. That's fine for orders, receipts
 * and shipments. Invoices must be gap-free and use a locked counter instead (see the invoicing
 * module).
 */
@Component
public class DocumentNumbers {

    /** The sequences that exist (see the migrations). An enum, so no SQL is ever built from input. */
    public enum Sequence {
        PURCHASE_ORDER("purchase_order_number_seq", "PO"),
        GOODS_RECEIPT("goods_receipt_number_seq", "GR"),
        SALES_ORDER("sales_order_number_seq", "SO"),
        SHIPMENT("shipment_number_seq", "SH"),
        STOCK_TRANSFER("stock_transfer_number_seq", "TR");

        private final String sequenceName;
        private final String prefix;

        Sequence(String sequenceName, String prefix) {
            this.sequenceName = sequenceName;
            this.prefix = prefix;
        }
    }

    private final JdbcClient jdbc;

    public DocumentNumbers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String next(Sequence sequence) {
        long value = jdbc.sql("SELECT nextval('" + sequence.sequenceName + "')").query(Long.class).single();
        return "%s-%06d".formatted(sequence.prefix, value);
    }
}
