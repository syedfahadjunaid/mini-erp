#!/bin/sh
# Runs once, the first time the Postgres volume is created.
#
# Least privilege, two logins for the API plus one for Keycloak:
#   erp_owner -> owns schema "erp" and runs the Flyway migrations (DDL, triggers)
#   erp_app   -> what the running API uses. The migrations grant it only what it needs: no DDL,
#                SELECT + INSERT on the stock ledger and invoices (never UPDATE or DELETE), and
#                read-only access to the stock balances (only the ledger trigger writes those).
#   keycloak  -> owns database "keycloak"
set -eu

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname "$POSTGRES_DB" \
     -v owner_pw="$DB_OWNER_PASSWORD" \
     -v app_pw="$DB_APP_PASSWORD" \
     -v kc_pw="$KEYCLOAK_DB_PASSWORD" <<'EOSQL'
-- :'name' makes psql quote the value safely as a SQL literal
CREATE ROLE erp_owner LOGIN PASSWORD :'owner_pw';
CREATE ROLE erp_app   LOGIN PASSWORD :'app_pw';
CREATE ROLE keycloak  LOGIN PASSWORD :'kc_pw';

CREATE DATABASE keycloak OWNER keycloak;
REVOKE CONNECT ON DATABASE keycloak FROM PUBLIC;

REVOKE CONNECT ON DATABASE erp FROM PUBLIC;
GRANT  CONNECT ON DATABASE erp TO erp_owner, erp_app;

CREATE SCHEMA erp AUTHORIZATION erp_owner;
GRANT USAGE ON SCHEMA erp TO erp_app;

ALTER ROLE erp_owner SET search_path = erp;
ALTER ROLE erp_app   SET search_path = erp;
EOSQL
