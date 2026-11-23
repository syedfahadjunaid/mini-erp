-- Server-side HTTP sessions for the web app (Spring Session JDBC).
--
-- The API is the web app's backend-for-frontend: after a browser signs in with Keycloak, the
-- OAuth2 tokens are kept HERE, in the session, and the browser only gets an HttpOnly cookie
-- (ERP_SESSION) that JavaScript can't read. Storing sessions in Postgres instead of memory
-- means they survive a restart and work with several API instances.
--
-- Same layout as Spring Session's schema-postgresql.sql; created by Flyway so that Flyway
-- owns every table (spring.session.jdbc.initialize-schema = never).

CREATE TABLE spring_session (
    primary_id            CHAR(36)     NOT NULL,
    session_id            CHAR(36)     NOT NULL,
    creation_time         BIGINT       NOT NULL,
    last_access_time      BIGINT       NOT NULL,
    max_inactive_interval INT          NOT NULL,
    expiry_time           BIGINT       NOT NULL,
    principal_name        VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id CHAR(36)     NOT NULL,
    attribute_name     VARCHAR(200) NOT NULL,
    attribute_bytes    BYTEA        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

-- Sessions are created, refreshed and expired (deleted) by the app itself.
GRANT SELECT, INSERT, UPDATE, DELETE ON spring_session, spring_session_attributes TO ${appRole};
