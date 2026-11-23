-- Flyway migration: runs once, in order, and is recorded in erp.flyway_schema_history.
-- Never edit a migration that has already run anywhere. Add V6__..., V7__... instead.
--
-- Conventions for the whole schema
--   * Surrogate keys are BIGINT identities; business keys (SKU, warehouse code, ...) are UNIQUE.
--   * Quantities that move stock are BIGINT in the product's BASE unit (EA, G, ML, CM, ...).
--     Quantities typed by people (10 BOX, 1.5 KG) are NUMERIC(18,3) next to their unit, and the
--     service converts them exactly into base units (it refuses 0.5 EA).
--   * Money is BIGINT minor units (cents) plus a 3-letter ISO 4217 currency. No floats anywhere.
--   * "Superkey" UNIQUE constraints such as UNIQUE (warehouse_id, id) look redundant: they exist
--     as targets for COMPOSITE foreign keys, which let the database itself reject a row that
--     points at a bin of another warehouse, a line of another order, and so on.
--   * ${appRole} is the login the running service uses. Each table grants it only what it needs.
--     Functions use "SET search_path FROM CURRENT", so they always resolve tables in this schema.

-- ---------------------------------------------------------------------------------------------
-- Units of measure and products
-- ---------------------------------------------------------------------------------------------

CREATE TABLE units_of_measure (
    code VARCHAR(10)  PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    CONSTRAINT units_of_measure_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]{0,9}$')
);

INSERT INTO units_of_measure (code, name) VALUES
    ('EA',   'Each'),
    ('PACK', 'Pack'),
    ('BOX',  'Box'),
    ('CASE', 'Case'),
    ('PAL',  'Pallet'),
    ('G',    'Gram'),
    ('KG',   'Kilogram'),
    ('ML',   'Millilitre'),
    ('L',    'Litre'),
    ('CM',   'Centimetre'),
    ('M',    'Metre');

CREATE TABLE products (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku         VARCHAR(40)  NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    -- All stock is counted in this unit. It can't change later (see the grants below), because
    -- every quantity in the stock ledger is expressed in it.
    base_uom    VARCHAR(10)  NOT NULL REFERENCES units_of_measure (code),
    -- Sales tax (VAT/GST) rate in basis points: 2000 = 20.00 %. Copied onto order lines.
    tax_rate_bp INTEGER      NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT products_sku_format CHECK (sku ~ '^[A-Z0-9][A-Z0-9._-]{1,39}$'),
    CONSTRAINT products_tax_rate_range CHECK (tax_rate_bp BETWEEN 0 AND 10000)
);

-- Every unit a product can be bought or sold in, and how many base units one of it holds:
-- for a product counted in EA, ('BOX', 12) means one box holds 12 each. The base unit itself
-- is always here with factor 1 (the trigger below inserts it), so order lines can reference
-- (product_id, uom_code) with a plain composite foreign key.
CREATE TABLE product_units (
    product_id         BIGINT      NOT NULL REFERENCES products (id),
    uom_code           VARCHAR(10) NOT NULL REFERENCES units_of_measure (code),
    base_units_per_uom BIGINT      NOT NULL,
    PRIMARY KEY (product_id, uom_code),
    CONSTRAINT product_units_factor_positive CHECK (base_units_per_uom > 0)
);

CREATE FUNCTION add_base_unit() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    INSERT INTO product_units (product_id, uom_code, base_units_per_uom) VALUES (NEW.id, NEW.base_uom, 1);
    RETURN NEW;
END;
$$;

CREATE TRIGGER products_add_base_unit
    AFTER INSERT ON products
    FOR EACH ROW EXECUTE FUNCTION add_base_unit();

-- ---------------------------------------------------------------------------------------------
-- Warehouses and bin locations
-- ---------------------------------------------------------------------------------------------

CREATE TABLE warehouses (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       VARCHAR(20)  NOT NULL UNIQUE,
    name       VARCHAR(200) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT warehouses_code_format CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{0,19}$')
);

-- A shelf, rack position or floor area inside one warehouse, e.g. "A-01-03".
CREATE TABLE bin_locations (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    warehouse_id BIGINT      NOT NULL REFERENCES warehouses (id),
    code         VARCHAR(30) NOT NULL,
    -- The bin where goods land when nobody says where to put them (the dock).
    is_receiving BOOLEAN     NOT NULL DEFAULT FALSE,
    active       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT bin_locations_code_format CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{0,29}$'),
    CONSTRAINT bin_locations_code_unique UNIQUE (warehouse_id, code),
    CONSTRAINT bin_locations_warehouse_superkey UNIQUE (warehouse_id, id)
);

-- At most one receiving bin per warehouse.
CREATE UNIQUE INDEX bin_locations_one_receiving_bin ON bin_locations (warehouse_id) WHERE is_receiving;

-- ---------------------------------------------------------------------------------------------
-- Business partners
-- ---------------------------------------------------------------------------------------------

CREATE TABLE suppliers (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code               VARCHAR(20)  NOT NULL UNIQUE,
    name               VARCHAR(200) NOT NULL,
    email              VARCHAR(254),
    -- The currency the supplier invoices in; purchase orders default to it.
    currency           VARCHAR(3)   NOT NULL,
    payment_terms_days INTEGER      NOT NULL,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT suppliers_code_format CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{0,19}$'),
    CONSTRAINT suppliers_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT suppliers_terms_range CHECK (payment_terms_days BETWEEN 0 AND 365)
);

CREATE TABLE customers (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code               VARCHAR(20)  NOT NULL UNIQUE,
    name               VARCHAR(200) NOT NULL,
    email              VARCHAR(254),
    -- Billing address, copied onto each invoice when it is issued (invoices never change).
    address_line1      VARCHAR(200) NOT NULL,
    address_line2      VARCHAR(200),
    postal_code        VARCHAR(20)  NOT NULL,
    city               VARCHAR(100) NOT NULL,
    country_code       VARCHAR(2)   NOT NULL,
    -- Sales orders and invoices for this customer are in this currency.
    currency           VARCHAR(3)   NOT NULL,
    payment_terms_days INTEGER      NOT NULL,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT customers_code_format CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{0,19}$'),
    CONSTRAINT customers_country_format CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT customers_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT customers_terms_range CHECK (payment_terms_days BETWEEN 0 AND 365)
);

-- ---------------------------------------------------------------------------------------------
-- Privileges for the running service. Column lists on UPDATE are deliberate: a product's base
-- unit, a bin's warehouse and every code are fixed once created.
-- ---------------------------------------------------------------------------------------------

GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO ${appRole};
GRANT SELECT ON units_of_measure TO ${appRole};
GRANT SELECT, INSERT ON products, product_units, warehouses, bin_locations, suppliers, customers TO ${appRole};
GRANT UPDATE (name, tax_rate_bp, active, version) ON products TO ${appRole};
GRANT UPDATE (name, active) ON warehouses TO ${appRole};
GRANT UPDATE (active) ON bin_locations TO ${appRole};
GRANT UPDATE (name, email, payment_terms_days, active) ON suppliers TO ${appRole};
GRANT UPDATE (name, email, address_line1, address_line2, postal_code, city, country_code, payment_terms_days, active)
    ON customers TO ${appRole};
