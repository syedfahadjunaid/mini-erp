package com.minierp.shared;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * "What time is it?" for the whole application. Everything asks this (or the {@link Clock}
 * bean behind it) instead of calling {@code Instant.now()}, so tests can move time.
 *
 * <p>Timestamps are stored as UTC instants; calendar dates (an invoice's issue date, its
 * fiscal year) are taken in the company's time zone.
 */
@Component
public class BusinessCalendar {

    private final Clock clock;
    private final ErpProperties props;

    public BusinessCalendar(Clock clock, ErpProperties props) {
        this.clock = clock;
        this.props = props;
    }

    public Instant now() {
        return clock.instant();
    }

    /** Today's date in the company's time zone. */
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), props.businessTimeZone());
    }
}
