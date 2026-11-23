-- Invoices, issued from shipments, numbered without gaps per fiscal year.
--
-- Why gap-free? In many countries invoice numbers must be consecutive, so an auditor can see
-- that no invoice was deleted. A Postgres SEQUENCE can't do that: nextval() is never rolled
-- back, so a failed transaction leaves a hole. Instead each year has one counter row:
--
--   UPDATE invoice_number_counters SET last_number = last_number + 1 WHERE fiscal_year = 2026
--   RETURNING last_number
--
-- The UPDATE locks the row until the transaction ends, so invoice numbering is serialised per
-- year (a deliberate trade-off: issuing invoices is not a hot path). If the transaction rolls
-- back, the increment rolls back with it and the number is handed out again. The triggers below
-- make sure every invoice uses the counter's current value and the counter only ever goes +1,
-- so numbers are 1, 2, 3, ... with no gaps and no duplicates, whatever the Java code does.

CREATE TABLE invoice_number_counters (
    fiscal_year INTEGER PRIMARY KEY,
    last_number INTEGER NOT NULL,
    CONSTRAINT invoice_number_counters_year_range CHECK (fiscal_year BETWEEN 2000 AND 9999),
    CONSTRAINT invoice_number_counters_non_negative CHECK (last_number >= 0)
);

CREATE TABLE invoices (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invoice_number    VARCHAR(20)  NOT NULL UNIQUE,      -- INV-2026-000042
    fiscal_year       INTEGER      NOT NULL REFERENCES invoice_number_counters (fiscal_year),
    sequence_number   INTEGER      NOT NULL,
    shipment_id       BIGINT       NOT NULL,
    sales_order_id    BIGINT       NOT NULL,
    customer_id       BIGINT       NOT NULL,
    currency          VARCHAR(3)   NOT NULL,
    issue_date        DATE         NOT NULL,
    due_date          DATE         NOT NULL,
    -- The customer's name and address when the invoice was issued. An invoice is a legal
    -- document: renaming the customer later must not change invoices already sent.
    bill_to_name      VARCHAR(200) NOT NULL,
    bill_to_address   VARCHAR(600) NOT NULL,
    net_total_minor   BIGINT       NOT NULL,
    tax_total_minor   BIGINT       NOT NULL,
    gross_total_minor BIGINT       NOT NULL,
    issued_by         VARCHAR(100) NOT NULL,
    issued_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT invoices_sequence_positive CHECK (sequence_number > 0),
    CONSTRAINT invoices_number_unique_per_year UNIQUE (fiscal_year, sequence_number),
    CONSTRAINT invoices_number_format CHECK (
        invoice_number = 'INV-' || fiscal_year::text || '-' || lpad(sequence_number::text, 6, '0')),
    CONSTRAINT invoices_year_matches_date CHECK (fiscal_year = EXTRACT(YEAR FROM issue_date)),
    CONSTRAINT invoices_due_after_issue CHECK (due_date >= issue_date),
    CONSTRAINT invoices_totals_add_up CHECK (gross_total_minor = net_total_minor + tax_total_minor),
    CONSTRAINT invoices_totals_non_negative CHECK (net_total_minor >= 0 AND tax_total_minor >= 0),
    -- One invoice per shipment, and it bills the order's customer in the order's currency.
    CONSTRAINT invoices_one_per_shipment UNIQUE (shipment_id),
    CONSTRAINT invoices_shipment FOREIGN KEY (shipment_id, sales_order_id) REFERENCES shipments (id, sales_order_id),
    CONSTRAINT invoices_order FOREIGN KEY (sales_order_id, customer_id, currency)
        REFERENCES sales_orders (id, customer_id, currency),
    CONSTRAINT invoices_shipment_superkey UNIQUE (id, shipment_id)
);
CREATE INDEX invoices_customer ON invoices (customer_id, issue_date);

CREATE TABLE invoice_lines (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invoice_id       BIGINT        NOT NULL,
    shipment_id      BIGINT        NOT NULL,
    shipment_line_id BIGINT        NOT NULL,
    line_no          INTEGER       NOT NULL,
    product_id       BIGINT        NOT NULL REFERENCES products (id),
    description      VARCHAR(250)  NOT NULL,
    uom_code         VARCHAR(10)   NOT NULL REFERENCES units_of_measure (code),
    quantity         NUMERIC(18,3) NOT NULL,
    unit_price_minor BIGINT        NOT NULL,
    tax_rate_bp      INTEGER       NOT NULL,
    net_minor        BIGINT        NOT NULL,
    tax_minor        BIGINT        NOT NULL,
    CONSTRAINT invoice_lines_line_no_unique UNIQUE (invoice_id, line_no),
    CONSTRAINT invoice_lines_quantity_positive CHECK (quantity > 0),
    CONSTRAINT invoice_lines_amounts_non_negative CHECK (unit_price_minor >= 0 AND net_minor >= 0 AND tax_minor >= 0),
    CONSTRAINT invoice_lines_tax_rate_range CHECK (tax_rate_bp BETWEEN 0 AND 10000),
    -- Each line bills a line of the invoice's own shipment, and no shipment line is billed twice.
    CONSTRAINT invoice_lines_invoice FOREIGN KEY (invoice_id, shipment_id) REFERENCES invoices (id, shipment_id),
    CONSTRAINT invoice_lines_shipment_line FOREIGN KEY (shipment_id, shipment_line_id)
        REFERENCES shipment_lines (shipment_id, id),
    CONSTRAINT invoice_lines_bill_once UNIQUE (shipment_line_id)
);

-- The counter only ever moves forward by exactly one.
CREATE FUNCTION check_invoice_counter_step() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF NEW.fiscal_year <> OLD.fiscal_year OR NEW.last_number <> OLD.last_number + 1 THEN
        RAISE EXCEPTION 'Invoice counter % may only be incremented by one (% -> %)',
            OLD.fiscal_year, OLD.last_number, NEW.last_number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER invoice_number_counters_step BEFORE UPDATE ON invoice_number_counters
    FOR EACH ROW EXECUTE FUNCTION check_invoice_counter_step();

-- A new invoice must take the number its own transaction just drew from the counter. Because
-- that transaction holds the counter row's lock, nobody else can have drawn a later number.
CREATE FUNCTION check_invoice_number_is_current() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    current_number INTEGER;
BEGIN
    SELECT last_number INTO current_number FROM invoice_number_counters WHERE fiscal_year = NEW.fiscal_year;
    IF current_number IS DISTINCT FROM NEW.sequence_number THEN
        RAISE EXCEPTION 'Invoice % must use the current counter value % of year %',
            NEW.invoice_number, current_number, NEW.fiscal_year
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER invoices_number_is_current BEFORE INSERT ON invoices
    FOR EACH ROW EXECUTE FUNCTION check_invoice_number_is_current();

-- ... and every number drawn must be used: at COMMIT, the invoice with the counter's new value
-- has to exist. (A transaction that draws a number and then decides not to invoice must roll
-- back, which also undoes the increment.) Deferred, because the counter is incremented before
-- the invoice row can be inserted.
CREATE FUNCTION check_invoice_number_used() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM invoices WHERE fiscal_year = NEW.fiscal_year AND sequence_number = NEW.last_number) THEN
        RAISE EXCEPTION 'Invoice number % of year % was drawn but no invoice uses it', NEW.last_number, NEW.fiscal_year
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER invoice_number_counters_number_used
    AFTER UPDATE ON invoice_number_counters
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_invoice_number_used();

-- Issued invoices never change (corrections would be credit notes, a later milestone).
CREATE TRIGGER invoices_append_only BEFORE UPDATE OR DELETE ON invoices
    FOR EACH ROW EXECUTE FUNCTION reject_change();
CREATE TRIGGER invoice_lines_append_only BEFORE UPDATE OR DELETE ON invoice_lines
    FOR EACH ROW EXECUTE FUNCTION reject_change();
CREATE TRIGGER invoice_number_counters_no_delete BEFORE DELETE ON invoice_number_counters
    FOR EACH ROW EXECUTE FUNCTION reject_change();

GRANT SELECT, INSERT ON invoices, invoice_lines, invoice_number_counters TO ${appRole};
GRANT UPDATE (last_number) ON invoice_number_counters TO ${appRole};
