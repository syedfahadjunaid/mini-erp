package com.minierp.shared;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Infrastructure switches that aren't specific to one module.
 *
 * <ul>
 *   <li>{@link EnableAsync}: event listeners annotated with {@code @ApplicationModuleListener}
 *       run asynchronously, after the publishing transaction has committed, on Spring Boot's
 *       task executor (virtual threads, see {@code spring.threads.virtual.enabled}).</li>
 *   <li>{@link EnableScheduling}: only when {@code erp.scheduling.enabled=true}, so tests decide
 *       themselves when background jobs run.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class PlatformConfig {

    /** The system clock in UTC. Tests replace it with a clock they can move. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnBooleanProperty("erp.scheduling.enabled")
    static class SchedulingConfig {
    }
}
