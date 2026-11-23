-- Purchase orders, goods receipts, sales orders and shipments.
--
-- Shape of every document: a header (who, where, status, version for optimistic locking) and
-- numbered lines. Lines repeat a few header columns (warehouse_id, purchase_order_id, ...) on
-- purpose: together with composite foreign keys this lets the database prove that a receipt
-- line belongs to a line of the SAME purchase order, that its bin is in the SAME warehouse, and
-- so on. Those repeated columns can't drift apart: the foreign keys would reject it.
--
-- Status values are checked here, but which transition is legal lives in Java
-- (PurchaseOrderStatus / SalesOrderStatus, exhaustive switches).

-- Document numbers like PO-000042. Sequences are not transactional, so a rolled-back order can
-- leave a gap here, which is fine for orders. Invoices need gap-free numbers: see V4.
CREATE SEQUENCE purchase_order_number_seq;
CREATE SEQUENCE goods_receipt_number_seq;
CREATE SEQUENCE sales_order_number_seq;
CREATE SEQUENCE shipment_number_seq;

-- ---------------------------------------------------------------------------------------------
-- Purchasing
-- ---------------------------------------------------------------------------------------------

CREATE TABLE purchase_orders (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_number  VARCHAR(20)   NOT NULL UNIQUE,
    supplier_id   BIGINT        NOT NULL REFERENCES suppliers (id),
    -- Goods are received into this warehouse.
    warehouse_id  BIGINT        NOT NULL REFERENCES warehouses (id),
    currency      VARCHAR(3)    NOT NULL,
    status        VARCHAR(20)   NOT NULL,
    expected_date DATE,
    notes         VARCHAR(1000),
    created_by    VARCHAR(100)  NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL,
    approved_by   VARCHAR(100),
    approved_at   TIMESTAMPTZ,
    version       BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT purchase_orders_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT purchase_orders_status_valid CHECK (status IN
        ('DRAFT', 'SUBMITTED', 'APPROVED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED')),
    -- Segregation of duties, also enforced by the service (403): nobody approves their own order.
    CONSTRAINT purchase_orders_approver_is_not_creator CHECK (approved_by IS NULL OR approved_by <> created_by),
    CONSTRAINT purchase_orders_approval_complete CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
    CONSTRAINT purchase_orders_warehouse_superkey UNIQUE (id, warehouse_id)
);

CREATE TABLE purchase_order_lines (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    purchase_order_id      BIGINT        NOT NULL REFERENCES purchase_orders (id),
    line_no                INTEGER       NOT NULL,
    product_id             BIGINT        NOT NULL,
    -- What the buyer typed: 10 BOX. base_quantity is the same amount in the product's base unit.
    uom_code               VARCHAR(10)   NOT NULL,
    quantity               NUMERIC(18,3) NOT NULL,
    base_quantity          BIGINT        NOT NULL,
    received_base_quantity BIGINT        NOT NULL DEFAULT 0,
    -- Price per uom_code unit; the line total is rounded once, half up, to the minor unit.
    unit_price_minor       BIGINT        NOT NULL,
    line_total_minor       BIGINT        NOT NULL,
    CONSTRAINT purchase_order_lines_line_no_positive CHECK (line_no > 0),
    CONSTRAINT purchase_order_lines_quantity_positive CHECK (quantity > 0 AND base_quantity > 0),
    CONSTRAINT purchase_order_lines_received_range CHECK (received_base_quantity BETWEEN 0 AND base_quantity),
    CONSTRAINT purchase_order_lines_amounts_non_negative CHECK (unit_price_minor >= 0 AND line_total_minor >= 0),
    CONSTRAINT purchase_order_lines_line_no_unique UNIQUE (purchase_order_id, line_no),
    -- The unit must be one this product is bought in.
    CONSTRAINT purchase_order_lines_unit FOREIGN KEY (product_id, uom_code) REFERENCES product_units (product_id, uom_code),
    CONSTRAINT purchase_order_lines_order_superkey UNIQUE (purchase_order_id, id),
    CONSTRAINT purchase_order_lines_product_superkey UNIQUE (id, product_id)
);

-- Who moved the order from which status to which, and when. Append-only (trigger in V3).
CREATE TABLE purchase_order_history (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    purchase_order_id BIGINT       NOT NULL REFERENCES purchase_orders (id),
    action            VARCHAR(20)  NOT NULL,
    from_status       VARCHAR(20),
    to_status         VARCHAR(20)  NOT NULL,
    actor             VARCHAR(100) NOT NULL,
    occurred_at       TIMESTAMPTZ  NOT NULL,
    comment           VARCHAR(500)
);
CREATE INDEX purchase_order_history_order ON purchase_order_history (purchase_order_id, id);

-- A delivery from the supplier, booked into one warehouse. Immutable once written.
CREATE TABLE goods_receipts (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    receipt_number    VARCHAR(20)  NOT NULL UNIQUE,
    purchase_order_id BIGINT       NOT NULL,
    warehouse_id      BIGINT       NOT NULL,
    received_by       VARCHAR(100) NOT NULL,
    received_at       TIMESTAMPTZ  NOT NULL,
    -- The receipt is for the warehouse the order said.
    CONSTRAINT goods_receipts_order FOREIGN KEY (purchase_order_id, warehouse_id)
        REFERENCES purchase_orders (id, warehouse_id),
    CONSTRAINT goods_receipts_order_superkey UNIQUE (id, purchase_order_id, warehouse_id)
);

CREATE TABLE goods_receipt_lines (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    goods_receipt_id       BIGINT        NOT NULL,
    purchase_order_id      BIGINT        NOT NULL,
    purchase_order_line_id BIGINT        NOT NULL,
    product_id             BIGINT        NOT NULL,
    warehouse_id           BIGINT        NOT NULL,
    bin_id                 BIGINT        NOT NULL,
    quantity               NUMERIC(18,3) NOT NULL,   -- in the order line's unit
    base_quantity          BIGINT        NOT NULL,
    CONSTRAINT goods_receipt_lines_quantity_positive CHECK (quantity > 0 AND base_quantity > 0),
    CONSTRAINT goods_receipt_lines_receipt FOREIGN KEY (goods_receipt_id, purchase_order_id, warehouse_id)
        REFERENCES goods_receipts (id, purchase_order_id, warehouse_id),
    -- ... a line of the same purchase order, for the same product ...
    CONSTRAINT goods_receipt_lines_order_line FOREIGN KEY (purchase_order_id, purchase_order_line_id)
        REFERENCES purchase_order_lines (purchase_order_id, id),
    CONSTRAINT goods_receipt_lines_product FOREIGN KEY (purchase_order_line_id, product_id)
        REFERENCES purchase_order_lines (id, product_id),
    -- ... into a bin of the same warehouse.
    CONSTRAINT goods_receipt_lines_bin FOREIGN KEY (warehouse_id, bin_id) REFERENCES bin_locations (warehouse_id, id),
    CONSTRAINT goods_receipt_lines_ledger_superkey UNIQUE (id, product_id, warehouse_id, bin_id)
);
CREATE INDEX goods_receipt_lines_receipt ON goods_receipt_lines (goods_receipt_id);

-- ---------------------------------------------------------------------------------------------
-- Sales
-- ---------------------------------------------------------------------------------------------

CREATE TABLE sales_orders (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_number VARCHAR(20)   NOT NULL UNIQUE,
    customer_id  BIGINT        NOT NULL REFERENCES customers (id),
    -- Stock is reserved in, and shipped from, this warehouse.
    warehouse_id BIGINT        NOT NULL REFERENCES warehouses (id),
    currency     VARCHAR(3)    NOT NULL,
    status       VARCHAR(20)   NOT NULL,
    notes        VARCHAR(1000),
    created_by   VARCHAR(100)  NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL,
    confirmed_by VARCHAR(100),
    confirmed_at TIMESTAMPTZ,
    version      BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT sales_orders_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT sales_orders_status_valid CHECK (status IN
        ('DRAFT', 'CONFIRMED', 'PARTIALLY_SHIPPED', 'SHIPPED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT sales_orders_confirmation_complete CHECK ((confirmed_by IS NULL) = (confirmed_at IS NULL)),
    CONSTRAINT sales_orders_warehouse_superkey UNIQUE (id, warehouse_id),
    CONSTRAINT sales_orders_billing_superkey UNIQUE (id, customer_id, currency)
);

CREATE TABLE sales_order_lines (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sales_order_id        BIGINT        NOT NULL,
    warehouse_id          BIGINT        NOT NULL,
    line_no               INTEGER       NOT NULL,
    product_id            BIGINT        NOT NULL,
    uom_code              VARCHAR(10)   NOT NULL,
    quantity              NUMERIC(18,3) NOT NULL,
    base_quantity         BIGINT        NOT NULL,
    shipped_base_quantity BIGINT        NOT NULL DEFAULT 0,
    unit_price_minor      BIGINT        NOT NULL,
    tax_rate_bp           INTEGER       NOT NULL,
    line_net_minor        BIGINT        NOT NULL,
    CONSTRAINT sales_order_lines_line_no_positive CHECK (line_no > 0),
    CONSTRAINT sales_order_lines_quantity_positive CHECK (quantity > 0 AND base_quantity > 0),
    CONSTRAINT sales_order_lines_shipped_range CHECK (shipped_base_quantity BETWEEN 0 AND base_quantity),
    CONSTRAINT sales_order_lines_amounts_non_negative CHECK (unit_price_minor >= 0 AND line_net_minor >= 0),
    CONSTRAINT sales_order_lines_tax_rate_range CHECK (tax_rate_bp BETWEEN 0 AND 10000),
    CONSTRAINT sales_order_lines_line_no_unique UNIQUE (sales_order_id, line_no),
    CONSTRAINT sales_order_lines_order FOREIGN KEY (sales_order_id, warehouse_id)
        REFERENCES sales_orders (id, warehouse_id),
    CONSTRAINT sales_order_lines_unit FOREIGN KEY (product_id, uom_code) REFERENCES product_units (product_id, uom_code),
    CONSTRAINT sales_order_lines_order_superkey UNIQUE (sales_order_id, id),
    CONSTRAINT sales_order_lines_ledger_superkey UNIQUE (id, product_id, warehouse_id)
);

CREATE TABLE sales_order_history (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sales_order_id BIGINT       NOT NULL REFERENCES sales_orders (id),
    action         VARCHAR(20)  NOT NULL,
    from_status    VARCHAR(20),
    to_status      VARCHAR(20)  NOT NULL,
    actor          VARCHAR(100) NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    comment        VARCHAR(500)
);
CREATE INDEX sales_order_history_order ON sales_order_history (sales_order_id, id);

-- Goods leaving the warehouse for the customer. Immutable once written; invoiced exactly once.
CREATE TABLE shipments (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shipment_number VARCHAR(20)  NOT NULL UNIQUE,
    sales_order_id  BIGINT       NOT NULL,
    warehouse_id    BIGINT       NOT NULL,
    shipped_by      VARCHAR(100) NOT NULL,
    shipped_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT shipments_order FOREIGN KEY (sales_order_id, warehouse_id) REFERENCES sales_orders (id, warehouse_id),
    CONSTRAINT shipments_order_superkey UNIQUE (id, sales_order_id, warehouse_id),
    CONSTRAINT shipments_invoice_superkey UNIQUE (id, sales_order_id)   -- target of invoices_shipment (V4)
);
CREATE INDEX shipments_order ON shipments (sales_order_id);

CREATE TABLE shipment_lines (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shipment_id         BIGINT        NOT NULL,
    sales_order_id      BIGINT        NOT NULL,
    sales_order_line_id BIGINT        NOT NULL,
    product_id          BIGINT        NOT NULL,
    warehouse_id        BIGINT        NOT NULL,
    quantity            NUMERIC(18,3) NOT NULL,   -- in the order line's unit
    base_quantity       BIGINT        NOT NULL,
    CONSTRAINT shipment_lines_quantity_positive CHECK (quantity > 0 AND base_quantity > 0),
    CONSTRAINT shipment_lines_shipment FOREIGN KEY (shipment_id, sales_order_id, warehouse_id)
        REFERENCES shipments (id, sales_order_id, warehouse_id),
    CONSTRAINT shipment_lines_order_line FOREIGN KEY (sales_order_id, sales_order_line_id)
        REFERENCES sales_order_lines (sales_order_id, id),
    CONSTRAINT shipment_lines_product FOREIGN KEY (sales_order_line_id, product_id, warehouse_id)
        REFERENCES sales_order_lines (id, product_id, warehouse_id),
    CONSTRAINT shipment_lines_line_unique UNIQUE (shipment_id, sales_order_line_id),
    CONSTRAINT shipment_lines_shipment_superkey UNIQUE (shipment_id, id),
    CONSTRAINT shipment_lines_ledger_superkey UNIQUE (id, product_id, warehouse_id)
);

-- ---------------------------------------------------------------------------------------------
-- Privileges. Receipts, shipments and history are insert-only for the service; order headers
-- can change only in the listed columns; lines can be replaced while an order is a draft (the
-- foreign keys stop a line from being deleted once anything was received or shipped against it).
-- ---------------------------------------------------------------------------------------------

GRANT USAGE ON SEQUENCE purchase_order_number_seq, goods_receipt_number_seq, sales_order_number_seq,
    shipment_number_seq TO ${appRole};

GRANT SELECT, INSERT ON purchase_orders, sales_orders TO ${appRole};
GRANT UPDATE (status, expected_date, notes, approved_by, approved_at, version) ON purchase_orders TO ${appRole};
GRANT UPDATE (status, notes, confirmed_by, confirmed_at, version) ON sales_orders TO ${appRole};

GRANT SELECT, INSERT, DELETE ON purchase_order_lines, sales_order_lines TO ${appRole};
GRANT UPDATE (received_base_quantity) ON purchase_order_lines TO ${appRole};
GRANT UPDATE (shipped_base_quantity) ON sales_order_lines TO ${appRole};

GRANT SELECT, INSERT ON purchase_order_history, sales_order_history, goods_receipts, goods_receipt_lines,
    shipments, shipment_lines TO ${appRole};
