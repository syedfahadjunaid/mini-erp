-- Reorder points, low-stock alerts, and Spring Modulith's event publication registry.

-- When AVAILABLE stock (on hand minus reserved) of a product in a warehouse drops to or below
-- reorder_point, a low-stock alert is raised; reorder_quantity is how much to buy.
CREATE TABLE reorder_rules (
    warehouse_id     BIGINT       NOT NULL REFERENCES warehouses (id),
    product_id       BIGINT       NOT NULL REFERENCES products (id),
    reorder_point    BIGINT       NOT NULL,    -- base units
    reorder_quantity BIGINT       NOT NULL,    -- base units
    updated_by       VARCHAR(100) NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (warehouse_id, product_id),
    CONSTRAINT reorder_rules_point_non_negative CHECK (reorder_point >= 0),
    CONSTRAINT reorder_rules_quantity_positive CHECK (reorder_quantity > 0)
);

-- OPEN -> ACKNOWLEDGED (someone is on it) -> RESOLVED (stock is back above the reorder point).
-- OPEN -> RESOLVED directly if stock comes back before anyone looked.
CREATE TABLE low_stock_alerts (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    warehouse_id      BIGINT       NOT NULL,
    product_id        BIGINT       NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    reorder_point     BIGINT       NOT NULL,     -- the rule at the time the alert was raised
    available_at_open BIGINT       NOT NULL,
    raised_at         TIMESTAMPTZ  NOT NULL,
    acknowledged_by   VARCHAR(100),
    acknowledged_at   TIMESTAMPTZ,
    resolved_at       TIMESTAMPTZ,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT low_stock_alerts_rule FOREIGN KEY (warehouse_id, product_id) REFERENCES reorder_rules (warehouse_id, product_id),
    CONSTRAINT low_stock_alerts_status_valid CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    CONSTRAINT low_stock_alerts_ack_complete CHECK ((acknowledged_by IS NULL) = (acknowledged_at IS NULL)),
    CONSTRAINT low_stock_alerts_resolved_complete CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL))
);

-- At most one unresolved alert per warehouse and product. Event listeners may run concurrently
-- (and events can be redelivered), so "raise an alert" is an INSERT ... ON CONFLICT DO NOTHING
-- against this index: idempotent, whatever the timing.
CREATE UNIQUE INDEX low_stock_alerts_one_active ON low_stock_alerts (warehouse_id, product_id)
    WHERE status <> 'RESOLVED';
CREATE INDEX low_stock_alerts_by_status ON low_stock_alerts (status, raised_at);

-- Spring Modulith event publication registry (schema "v2" from spring-modulith-events-jdbc 2.2,
-- created here instead of by Modulith so that Flyway owns every table). A domain event is
-- written here in the SAME transaction as the change that published it, one row per listener;
-- the row is marked completed when the listener succeeds, so an event survives a crash or a
-- failing listener and is delivered again (see EventPublicationMaintenance).
CREATE TABLE event_publication (
    id                     UUID                     NOT NULL,
    listener_id            TEXT                     NOT NULL,
    event_type             TEXT                     NOT NULL,
    serialized_event       TEXT                     NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);

GRANT SELECT, INSERT ON reorder_rules, low_stock_alerts TO ${appRole};
GRANT UPDATE (reorder_point, reorder_quantity, updated_by, updated_at, version) ON reorder_rules TO ${appRole};
GRANT UPDATE (status, acknowledged_by, acknowledged_at, resolved_at, version) ON low_stock_alerts TO ${appRole};
GRANT SELECT, INSERT, UPDATE, DELETE ON event_publication TO ${appRole};
