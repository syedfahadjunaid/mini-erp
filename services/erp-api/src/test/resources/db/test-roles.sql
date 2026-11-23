-- Test database roles, like infra/postgres/init/01-init.sh does in Docker Compose:
-- Flyway runs as the container superuser (the schema owner), the app as erp_app.
CREATE ROLE erp_app LOGIN PASSWORD 'erp-app-test';
ALTER ROLE erp_app SET search_path = erp;
