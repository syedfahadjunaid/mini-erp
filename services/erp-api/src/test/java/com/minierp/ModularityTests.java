package com.minierp;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * The module rules, checked on every build (Spring Modulith, no Spring context needed):
 * modules only use each other's public types, and their dependencies form no cycle. E.g. sales
 * may not depend on invoicing: invoicing reacts to sales' ShipmentDispatched event instead.
 */
class ModularityTests {

    @Test
    void modulesRespectTheirBoundaries() {
        ApplicationModules.of(MiniErpApplication.class).verify();
    }
}
