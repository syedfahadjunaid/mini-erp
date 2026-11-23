package com.minierp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * A modular monolith: one deployable, split into modules that are the direct sub-packages of
 * this one ({@code catalog}, {@code partners}, {@code inventory}, {@code purchasing},
 * {@code sales}, {@code invoicing}, {@code replenishment}, {@code reporting}, and {@code shared}).
 *
 * <p>Spring Modulith checks the rules in a test ({@code ModularityTests}): modules only use
 * each other's public types, and the dependencies between them form no cycle. Modules that
 * react to something another module did (low-stock alerts, automatic invoicing) do it through
 * domain events instead of direct calls, so the module that publishes doesn't depend on them.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MiniErpApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniErpApplication.class, args);
    }
}
