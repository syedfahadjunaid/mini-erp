package com.minierp;

import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Postgres 18 in a throwaway Docker container (Testcontainers). No in-memory database
 * and no mocks: the migrations, triggers, grants, row locks and constraints under test are the
 * real ones.
 *
 * <p>Like production, Flyway runs as the schema owner (the container's superuser here) and the
 * application as {@code erp_app}, with only the privileges the migrations grant. So a test also
 * fails if the app tries to do something its database login may not.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final String APP_ROLE = "erp_app";
    static final String APP_PASSWORD = "erp-app-test";

    @Bean
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                .withDatabaseName("erp")
                .withInitScript("db/test-roles.sql")
                // The default is 60 s. On a slow or busy machine Postgres can take longer to
                // report "ready"; failing then would be a false alarm, not a bug.
                .withStartupTimeout(Duration.ofMinutes(3));
    }

    @Bean
    DynamicPropertyRegistrar containerProperties(PostgreSQLContainer postgres) {
        return registry -> {
            registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=erp");
            registry.add("spring.datasource.username", () -> APP_ROLE);
            registry.add("spring.datasource.password", () -> APP_PASSWORD);
            registry.add("spring.flyway.user", postgres::getUsername);
            registry.add("spring.flyway.password", postgres::getPassword);
            registry.add("spring.flyway.placeholders.appRole", () -> APP_ROLE);
        };
    }
}
