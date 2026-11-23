-- The stock ledger.
--
--   stock_movements  append-only: every change to physical stock (on hand) or to promised stock
--                    (reserved) is one row, with its reason (a typed link to the document line
--                    that caused it), who recorded it and when.
--   stock_levels     per warehouse and product: on hand, reserved, available = on hand - reserved
--   bin_stock        per bin and product: on hand
--
-- The two balance tables are a projection of the ledger that the DATABASE maintains: a trigger
-- applies each movement to them in the same statement that inserts it. The service has no
-- privilege to write them at all, so "balance = sum of movements" can't be broken by a bug in
-- Java, a forgotten code path or a manual UPDATE as the app user. The trigger also takes the row
-- lock that makes concurrent reservations safe (see apply_stock_movement below).
--
-- Rules checked here (Java checks them first, to give friendly errors; these are the backstop):
--   * available never goes below zero: you can't reserve, transfer or adjust away stock that is
--     promised to a customer, and two orders can't both take the last unit;
--   * a bin never goes below zero;
--   * each movement type has a fixed shape (signs, bin or no bin, exactly one source link).

-- ---------------------------------------------------------------------------------------------
-- Append-only guard, shared by every history-like table
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION reject_change() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER purchase_order_history_append_only BEFORE UPDATE OR DELETE ON purchase_order_history
    FOR EACH ROW EXECUTE FUNCTION reject_change();
CREATE TRIGGER sales_order_history_append_only BEFORE UPDATE OR DELETE ON sales_order_history
    FOR EACH ROW EXECUTE FUNCTION reject_change();
CREATE TRIGGER goods_receipts_append_only BEFORE UPDATE OR DELETE ON goods_receipts
    FOR EACH ROW EXECUTE FUNCTION reject_change();
CREATE TRIGGER goods_receipt_lines_append_only BEFORE UPDATE OR DELETE ON goods_receipt_lines
    FOR EACH ROW EXECUTE FUNCTION reject_change();
CREATE TRIGGER shipments_append_only BEFORE UPDATE OR DELETE ON shipments
    FOR EACH ROW EXECUTE FUNCTION reject_change();
CREATE TRIGGER shipment_lines_append_only BEFORE UPDATE OR DELETE ON shipment_lines
    FOR EACH ROW EXECUTE FUNCTION reject_change();

-- ---------------------------------------------------------------------------------------------
-- Documents that only move stock: adjustments and inter-warehouse transfers
-- ---------------------------------------------------------------------------------------------

-- A correction after a stock count, damage, etc. One product, one bin, signed quantity.
CREATE TABLE stock_adjustments (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    warehouse_id   BIGINT       NOT NULL,
    bin_id         BIGINT       NOT NULL,
    product_id     BIGINT       NOT NULL REFERENCES products (id),
    quantity_delta BIGINT       NOT NULL,
    reason         VARCHAR(500) NOT NULL,
    created_by     VARCHAR(100) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT stock_adjustments_delta_non_zero CHECK (quantity_delta <> 0),
    CONSTRAINT stock_adjustments_bin FOREIGN KEY (warehouse_id, bin_id) REFERENCES bin_locations (warehouse_id, id),
    CONSTRAINT stock_adjustments_ledger_superkey UNIQUE (id, product_id, warehouse_id, bin_id)
);

CREATE SEQUENCE stock_transfer_number_seq;

-- Stock leaves the source bins when the transfer is created (IN_TRANSIT) and arrives in the
-- destination bin when it is received (RECEIVED). In between it is in neither warehouse.
CREATE TABLE stock_transfers (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_number   VARCHAR(20)  NOT NULL UNIQUE,
    from_warehouse_id BIGINT       NOT NULL REFERENCES warehouses (id),
    to_warehouse_id   BIGINT       NOT NULL REFERENCES warehouses (id),
    status            VARCHAR(20)  NOT NULL,
    created_by        VARCHAR(100) NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    received_by       VARCHAR(100),
    received_at       TIMESTAMPTZ,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT stock_transfers_between_warehouses CHECK (from_warehouse_id <> to_warehouse_id),
    CONSTRAINT stock_transfers_status_valid CHECK (status IN ('IN_TRANSIT', 'RECEIVED')),
    CONSTRAINT stock_transfers_receipt_complete CHECK (
        (status = 'RECEIVED') = (received_by IS NOT NULL) AND (received_by IS NULL) = (received_at IS NULL)),
    CONSTRAINT stock_transfers_route_superkey UNIQUE (id, from_warehouse_id, to_warehouse_id)
);

CREATE TABLE stock_transfer_lines (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id       BIGINT  NOT NULL,
    line_no           INTEGER NOT NULL,
    product_id        BIGINT  NOT NULL REFERENCES products (id),
    quantity          BIGINT  NOT NULL,           -- base units
    from_warehouse_id BIGINT  NOT NULL,
    from_bin_id       BIGINT  NOT NULL,
    to_warehouse_id   BIGINT  NOT NULL,
    to_bin_id         BIGINT,                     -- chosen when the goods arrive
    CONSTRAINT stock_transfer_lines_quantity_positive CHECK (quantity > 0),
    CONSTRAINT stock_transfer_lines_line_no_unique UNIQUE (transfer_id, line_no),
    CONSTRAINT stock_transfer_lines_transfer FOREIGN KEY (transfer_id, from_warehouse_id, to_warehouse_id)
        REFERENCES stock_transfers (id, from_warehouse_id, to_warehouse_id),
    CONSTRAINT stock_transfer_lines_from_bin FOREIGN KEY (from_warehouse_id, from_bin_id)
        REFERENCES bin_locations (warehouse_id, id),
    CONSTRAINT stock_transfer_lines_to_bin FOREIGN KEY (to_warehouse_id, to_bin_id)
        REFERENCES bin_locations (warehouse_id, id),
    CONSTRAINT stock_transfer_lines_out_superkey UNIQUE (id, product_id, from_warehouse_id, from_bin_id),
    CONSTRAINT stock_transfer_lines_in_superkey UNIQUE (id, product_id, to_warehouse_id, to_bin_id)
);

-- ---------------------------------------------------------------------------------------------
-- Balances (maintained by the trigger only)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE stock_levels (
    warehouse_id   BIGINT      NOT NULL REFERENCES warehouses (id),
    product_id     BIGINT      NOT NULL REFERENCES products (id),
    on_hand        BIGINT      NOT NULL DEFAULT 0,
    reserved       BIGINT      NOT NULL DEFAULT 0,
    available      BIGINT      GENERATED ALWAYS AS (on_hand - reserved) STORED,
    -- How many movements this balance has absorbed; also the last movement's level_seq.
    movement_count BIGINT      NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (warehouse_id, product_id),
    CONSTRAINT stock_levels_on_hand_non_negative CHECK (on_hand >= 0),
    CONSTRAINT stock_levels_reserved_non_negative CHECK (reserved >= 0),
    CONSTRAINT stock_levels_reserved_within_on_hand CHECK (reserved <= on_hand)
);

CREATE TABLE bin_stock (
    bin_id       BIGINT      NOT NULL,
    product_id   BIGINT      NOT NULL REFERENCES products (id),
    warehouse_id BIGINT      NOT NULL,
    on_hand      BIGINT      NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (bin_id, product_id),
    CONSTRAINT bin_stock_bin FOREIGN KEY (warehouse_id, bin_id) REFERENCES bin_locations (warehouse_id, id),
    CONSTRAINT bin_stock_on_hand_non_negative CHECK (on_hand >= 0)
);
CREATE INDEX bin_stock_by_product ON bin_stock (warehouse_id, product_id);

-- ---------------------------------------------------------------------------------------------
-- The ledger
-- ---------------------------------------------------------------------------------------------

CREATE TABLE stock_movements (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    movement_type         VARCHAR(20)  NOT NULL,
    warehouse_id          BIGINT       NOT NULL REFERENCES warehouses (id),
    bin_id                BIGINT,                       -- NULL for reservations (warehouse level)
    product_id            BIGINT       NOT NULL REFERENCES products (id),
    on_hand_delta         BIGINT       NOT NULL,
    reserved_delta        BIGINT       NOT NULL,
    -- Filled in by the trigger: this movement's position in its (warehouse, product) history,
    -- and the warehouse balances right after it. They make every movement self-checking: the
    -- reconciliation recomputes the running totals and compares.
    level_seq             BIGINT       NOT NULL,
    on_hand_after         BIGINT       NOT NULL,
    reserved_after        BIGINT       NOT NULL,
    -- Why the stock moved: exactly one of these, matching movement_type (see the CHECK below).
    goods_receipt_line_id BIGINT,
    sales_order_line_id   BIGINT,
    shipment_line_id      BIGINT,
    transfer_out_line_id  BIGINT,
    transfer_in_line_id   BIGINT,
    adjustment_id         BIGINT,
    occurred_at           TIMESTAMPTZ  NOT NULL,
    recorded_by           VARCHAR(100) NOT NULL,

    CONSTRAINT stock_movements_type_valid CHECK (movement_type IN
        ('RECEIPT', 'RESERVE', 'RELEASE', 'SHIPMENT', 'TRANSFER_OUT', 'TRANSFER_IN', 'ADJUSTMENT')),
    CONSTRAINT stock_movements_one_source CHECK (num_nonnulls(goods_receipt_line_id, sales_order_line_id,
        shipment_line_id, transfer_out_line_id, transfer_in_line_id, adjustment_id) = 1),
    CONSTRAINT stock_movements_shape CHECK (CASE movement_type
        WHEN 'RECEIPT'      THEN on_hand_delta > 0 AND reserved_delta = 0 AND bin_id IS NOT NULL
                                 AND goods_receipt_line_id IS NOT NULL
        WHEN 'RESERVE'      THEN on_hand_delta = 0 AND reserved_delta > 0 AND bin_id IS NULL
                                 AND sales_order_line_id IS NOT NULL
        WHEN 'RELEASE'      THEN on_hand_delta = 0 AND reserved_delta < 0 AND bin_id IS NULL
                                 AND sales_order_line_id IS NOT NULL
        -- Shipping consumes the reservation: on hand and reserved drop by the same amount.
        WHEN 'SHIPMENT'     THEN on_hand_delta < 0 AND reserved_delta = on_hand_delta AND bin_id IS NOT NULL
                                 AND shipment_line_id IS NOT NULL
        WHEN 'TRANSFER_OUT' THEN on_hand_delta < 0 AND reserved_delta = 0 AND bin_id IS NOT NULL
                                 AND transfer_out_line_id IS NOT NULL
        WHEN 'TRANSFER_IN'  THEN on_hand_delta > 0 AND reserved_delta = 0 AND bin_id IS NOT NULL
                                 AND transfer_in_line_id IS NOT NULL
        WHEN 'ADJUSTMENT'   THEN on_hand_delta <> 0 AND reserved_delta = 0 AND bin_id IS NOT NULL
                                 AND adjustment_id IS NOT NULL
        ELSE FALSE END),

    -- A movement can't disagree with its source document about product, warehouse or bin.
    CONSTRAINT stock_movements_bin FOREIGN KEY (warehouse_id, bin_id) REFERENCES bin_locations (warehouse_id, id),
    CONSTRAINT stock_movements_receipt_line FOREIGN KEY (goods_receipt_line_id, product_id, warehouse_id, bin_id)
        REFERENCES goods_receipt_lines (id, product_id, warehouse_id, bin_id),
    CONSTRAINT stock_movements_order_line FOREIGN KEY (sales_order_line_id, product_id, warehouse_id)
        REFERENCES sales_order_lines (id, product_id, warehouse_id),
    CONSTRAINT stock_movements_shipment_line FOREIGN KEY (shipment_line_id, product_id, warehouse_id)
        REFERENCES shipment_lines (id, product_id, warehouse_id),
    CONSTRAINT stock_movements_transfer_out FOREIGN KEY (transfer_out_line_id, product_id, warehouse_id, bin_id)
        REFERENCES stock_transfer_lines (id, product_id, from_warehouse_id, from_bin_id),
    CONSTRAINT stock_movements_transfer_in FOREIGN KEY (transfer_in_line_id, product_id, warehouse_id, bin_id)
        REFERENCES stock_transfer_lines (id, product_id, to_warehouse_id, to_bin_id),
    CONSTRAINT stock_movements_adjustment FOREIGN KEY (adjustment_id, product_id, warehouse_id, bin_id)
        REFERENCES stock_adjustments (id, product_id, warehouse_id, bin_id),

    CONSTRAINT stock_movements_level_seq_unique UNIQUE (warehouse_id, product_id, level_seq)
);

-- A document line is posted to the ledger at most once (a retried request can't double-count).
CREATE UNIQUE INDEX stock_movements_receipt_once ON stock_movements (goods_receipt_line_id)
    WHERE goods_receipt_line_id IS NOT NULL;
CREATE UNIQUE INDEX stock_movements_reserve_once ON stock_movements (sales_order_line_id)
    WHERE movement_type = 'RESERVE';
CREATE UNIQUE INDEX stock_movements_release_once ON stock_movements (sales_order_line_id)
    WHERE movement_type = 'RELEASE';
CREATE UNIQUE INDEX stock_movements_shipment_bin_once ON stock_movements (shipment_line_id, bin_id)
    WHERE shipment_line_id IS NOT NULL;
CREATE UNIQUE INDEX stock_movements_transfer_out_once ON stock_movements (transfer_out_line_id)
    WHERE transfer_out_line_id IS NOT NULL;
CREATE UNIQUE INDEX stock_movements_transfer_in_once ON stock_movements (transfer_in_line_id)
    WHERE transfer_in_line_id IS NOT NULL;
CREATE UNIQUE INDEX stock_movements_adjustment_once ON stock_movements (adjustment_id)
    WHERE adjustment_id IS NOT NULL;

CREATE INDEX stock_movements_by_product ON stock_movements (product_id, occurred_at);
CREATE INDEX stock_movements_by_time ON stock_movements (occurred_at);

-- ---------------------------------------------------------------------------------------------
-- The trigger that applies a movement to the balances
-- ---------------------------------------------------------------------------------------------
--
-- Concurrency: the SELECT ... FOR UPDATE locks the (warehouse, product) balance row until the
-- transaction ends. A second transaction moving the same product in the same warehouse waits
-- there, then reads the balance the first one committed. So two orders racing for the last unit
-- are serialised: the second sees available = 0 and fails with SQLSTATE ER001.
--
-- Errors are raised with our own SQLSTATE codes and a JSON DETAIL, which StockLedger turns into
-- a 422 response with the numbers:
--   ER001  not enough AVAILABLE stock in the warehouse (on hand minus reserved)
--   ER002  not enough stock in the BIN
--
-- SECURITY DEFINER: the function runs with its owner's rights, because the app login may not
-- write the balance tables itself.

CREATE FUNCTION apply_stock_movement() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
DECLARE
    level         stock_levels%ROWTYPE;
    bin_on_hand   BIGINT;
    new_on_hand   BIGINT;
    new_reserved  BIGINT;
BEGIN
    -- Make sure the balance rows exist (first movement of this product here), then lock them.
    INSERT INTO stock_levels (warehouse_id, product_id, updated_at)
    VALUES (NEW.warehouse_id, NEW.product_id, NEW.occurred_at)
    ON CONFLICT (warehouse_id, product_id) DO NOTHING;

    SELECT * INTO level FROM stock_levels
     WHERE warehouse_id = NEW.warehouse_id AND product_id = NEW.product_id
       FOR UPDATE;

    new_on_hand  := level.on_hand + NEW.on_hand_delta;
    new_reserved := level.reserved + NEW.reserved_delta;

    -- Only movements that reduce availability are checked; receipts always fit.
    IF new_on_hand - new_reserved < 0 AND NEW.on_hand_delta - NEW.reserved_delta < 0 THEN
        RAISE EXCEPTION 'Not enough available stock of product % in warehouse %', NEW.product_id, NEW.warehouse_id
            USING ERRCODE = 'ER001',
                  DETAIL = json_build_object(
                      'warehouseId', NEW.warehouse_id, 'productId', NEW.product_id,
                      'onHand', level.on_hand, 'reserved', level.reserved,
                      'available', level.on_hand - level.reserved,
                      'requested', NEW.reserved_delta - NEW.on_hand_delta)::text;
    END IF;

    UPDATE stock_levels
       SET on_hand = new_on_hand,
           reserved = new_reserved,
           movement_count = movement_count + 1,
           updated_at = NEW.occurred_at
     WHERE warehouse_id = NEW.warehouse_id AND product_id = NEW.product_id;

    IF NEW.bin_id IS NOT NULL THEN
        INSERT INTO bin_stock (bin_id, product_id, warehouse_id, updated_at)
        VALUES (NEW.bin_id, NEW.product_id, NEW.warehouse_id, NEW.occurred_at)
        ON CONFLICT (bin_id, product_id) DO NOTHING;

        SELECT on_hand INTO bin_on_hand FROM bin_stock
         WHERE bin_id = NEW.bin_id AND product_id = NEW.product_id
           FOR UPDATE;

        IF bin_on_hand + NEW.on_hand_delta < 0 THEN
            RAISE EXCEPTION 'Not enough stock of product % in bin %', NEW.product_id, NEW.bin_id
                USING ERRCODE = 'ER002',
                      DETAIL = json_build_object(
                          'warehouseId', NEW.warehouse_id, 'binId', NEW.bin_id, 'productId', NEW.product_id,
                          'onHand', bin_on_hand, 'requested', -NEW.on_hand_delta)::text;
        END IF;

        UPDATE bin_stock
           SET on_hand = on_hand + NEW.on_hand_delta,
               updated_at = NEW.occurred_at
         WHERE bin_id = NEW.bin_id AND product_id = NEW.product_id;
    END IF;

    NEW.level_seq      := level.movement_count + 1;
    NEW.on_hand_after  := new_on_hand;
    NEW.reserved_after := new_reserved;
    RETURN NEW;
END;
$$;

-- The function runs as its owner; nobody else needs to call it directly.
REVOKE ALL ON FUNCTION apply_stock_movement() FROM PUBLIC;

CREATE TRIGGER stock_movements_apply
    BEFORE INSERT ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION apply_stock_movement();

CREATE TRIGGER stock_movements_append_only BEFORE UPDATE OR DELETE ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION reject_change();
CREATE TRIGGER stock_movements_no_truncate BEFORE TRUNCATE ON stock_movements
    FOR EACH STATEMENT EXECUTE FUNCTION reject_change();
CREATE TRIGGER stock_adjustments_append_only BEFORE UPDATE OR DELETE ON stock_adjustments
    FOR EACH ROW EXECUTE FUNCTION reject_change();

-- ---------------------------------------------------------------------------------------------
-- Privileges: the ledger is insert-only, the balances are read-only for the service.
-- ---------------------------------------------------------------------------------------------

GRANT USAGE ON SEQUENCE stock_transfer_number_seq TO ${appRole};
GRANT SELECT, INSERT ON stock_movements, stock_adjustments, stock_transfers, stock_transfer_lines TO ${appRole};
GRANT UPDATE (status, received_by, received_at, version) ON stock_transfers TO ${appRole};
GRANT UPDATE (to_bin_id) ON stock_transfer_lines TO ${appRole};
GRANT SELECT ON stock_levels, bin_stock TO ${appRole};
