package com.minierp.shared;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application settings under {@code erp.*} in application.yml, bound once at startup.
 *
 * @param publicUrl        where people reach the web app (nginx), e.g. http://localhost:5780. The
 *                         sign-in redirect URI is built from it, never from request headers,
 *                         which a client controls
 * @param businessTimeZone the company's time zone: it decides an invoice's date and therefore
 *                         its fiscal year (an invoice issued at 23:30 UTC on 31 December is
 *                         already next year's in Auckland)
 * @param keycloak         browser sign-in and bearer-token checks
 * @param invoicing        invoicing behaviour
 * @param events           maintenance of the event publication registry
 * @param scheduling       background jobs (tests switch them off and run them by hand)
 * @param company          the seller printed on invoices
 */
@ConfigurationProperties("erp")
public record ErpProperties(
        String publicUrl,
        ZoneId businessTimeZone,
        Keycloak keycloak,
        Invoicing invoicing,
        Events events,
        Scheduling scheduling,
        Company company) {

    /**
     * @param issuerUri      the realm as browsers and scripts see it; the {@code iss} of every token
     * @param backchannelUri the same realm as this service reaches it (inside Docker:
     *                       http://keycloak:8080/realms/erp, because "localhost" there is the container)
     * @param audience       the {@code aud} every bearer token must carry: tokens for other apps are refused
     * @param clientId       this app's confidential client for browser sign-in ("erp-web")
     * @param clientSecret   its secret (from .env; the browser never sees it)
     */
    public record Keycloak(String issuerUri, String backchannelUri, String audience, String clientId, String clientSecret) {}

    /** @param autoIssue issue an invoice automatically for every shipment (via a domain event) */
    public record Invoicing(boolean autoIssue) {}

    /**
     * @param resubmitFailedAfter how old a failed event publication must be before it is retried
     * @param keepCompletedFor    how long completed publications are kept (for debugging) before deletion
     */
    public record Events(Duration resubmitFailedAfter, Duration keepCompletedFor) {}

    /** @param enabled whether the scheduled jobs run */
    public record Scheduling(boolean enabled) {}

    /**
     * @param name    legal name of the seller
     * @param address postal address; a line break separates the lines
     * @param taxId   VAT / GST registration number
     */
    public record Company(String name, String address, String taxId) {}
}
