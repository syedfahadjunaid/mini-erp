package com.minierp.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Housekeeping for Spring Modulith's event publication registry (table {@code event_publication}).
 *
 * <p>How domain events flow: a service publishes an event inside its transaction; Modulith stores
 * one row per interested listener in the same transaction; after commit, each listener runs in
 * its own transaction and its row is marked completed. If a listener throws (say, the database
 * was briefly unavailable), its row is marked failed and the event is NOT lost:
 * {@link #resubmitFailed()} hands it to the listener again. That gives at-least-once delivery,
 * which is why every listener in this app is idempotent.
 */
@Component
public class EventPublicationMaintenance {

    private static final Logger log = LoggerFactory.getLogger(EventPublicationMaintenance.class);

    private final FailedEventPublications failed;
    private final CompletedEventPublications completed;
    private final ErpProperties props;

    public EventPublicationMaintenance(FailedEventPublications failed, CompletedEventPublications completed,
            ErpProperties props) {
        this.failed = failed;
        this.completed = completed;
        this.props = props;
    }

    /** Retries failed listener invocations once they are a little old (gives a flaky dependency time). */
    @Scheduled(fixedDelayString = "${erp.events.resubmit-interval:30s}")
    public void resubmitFailed() {
        failed.resubmit(ResubmissionOptions.defaults()
                .withMinAge(props.events().resubmitFailedAfter())
                .withBatchSize(100));
    }

    /** Completed rows are only kept for a while, for debugging. */
    @Scheduled(fixedDelayString = "${erp.events.cleanup-interval:1h}")
    public void deleteOldCompleted() {
        completed.deletePublicationsOlderThan(props.events().keepCompletedFor());
        log.debug("Deleted completed event publications older than {}", props.events().keepCompletedFor());
    }
}
