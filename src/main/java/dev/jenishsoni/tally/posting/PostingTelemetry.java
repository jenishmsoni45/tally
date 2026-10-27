package dev.jenishsoni.tally.posting;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import dev.jenishsoni.tally.common.ConflictException;
import dev.jenishsoni.tally.common.NotFoundException;
import dev.jenishsoni.tally.common.TelemetryKeys;
import dev.jenishsoni.tally.common.UnprocessableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Times, counts and logs every posting. The controller calls it around the service, outside the
 * transactional proxy, so the timer includes the commit, where the deferred balance trigger runs, and an
 * entry is logged as posted only once it has committed.
 */
@Component
class PostingTelemetry {

    private static final Logger log = LoggerFactory.getLogger(PostingTelemetry.class);

    private static final String DURATION = "ledger.posting.duration";
    private static final String POSTINGS = "ledger.postings";

    private static final String OPERATION = "operation";
    private static final String OUTCOME = "outcome";
    private static final String KIND = "kind";

    private static final String POSTED = "posted";
    private static final String REPLAYED = "replayed";
    // A refusal the API answers with a 4xx, as opposed to a failure.
    private static final String REJECTED = "rejected";
    private static final String FAILED = "failed";

    private final MeterRegistry registry;
    private final Meter.MeterProvider<Timer> durations;
    private final Meter.MeterProvider<Counter> postings;
    private final ObservationRegistry observations;

    PostingTelemetry(MeterRegistry registry, ObservationRegistry observations) {
        this.registry = registry;
        this.durations = Timer.builder(DURATION)
                .description("Time to post an entry, commit included, by operation and outcome")
                .withRegistry(registry);
        this.postings = Counter.builder(POSTINGS)
                .description("Entries posted, by kind; replayed requests are not counted")
                .withRegistry(registry);
        this.observations = observations;
    }

    /** Runs one posting with the key in the log context, so every line it logs can be found by the key. */
    PostingResult observe(String operation, String idempotencyKey, Supplier<PostingResult> posting) {
        try (MDC.MDCCloseable _ = MDC.putCloseable(TelemetryKeys.IDEMPOTENCY_KEY, idempotencyKey)) {
            PostingResult result = timed(operation, posting);
            announce(result, idempotencyKey);
            return result;
        }
    }

    private PostingResult timed(String operation, Supplier<PostingResult> posting) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = FAILED;
        try {
            PostingResult result = posting.get();
            outcome = result.replayed() ? REPLAYED : POSTED;
            return result;
        } catch (ConflictException | UnprocessableException | NotFoundException e) {
            outcome = REJECTED;
            throw e;
        } catch (RuntimeException e) {
            // Logged here, while the idempotency key is still in the log context. The exception is logged again
            // once observe() has returned, by ApiExceptionHandler for a database failure and by the servlet
            // container otherwise, and that line no longer carries the key.
            log.error("Posting {} failed", operation, e);
            throw e;
        } finally {
            sample.stop(durations.withTags(OPERATION, operation, OUTCOME, outcome));
        }
    }

    private void announce(PostingResult result, String idempotencyKey) {
        JournalEntry entry = result.entry();
        String entryId = entry.id().toString();
        tagRequestSpan(entryId);
        try (MDC.MDCCloseable _ = MDC.putCloseable(TelemetryKeys.ENTRY_ID, entryId)) {
            if (result.replayed()) {
                log.info("Replayed entry {} idempotencyKey={}", entry.id(), idempotencyKey);
                return;
            }
            postings.withTag(KIND, entry.kind()).increment();
            log.info("Posted entry {} kind={} lines={} idempotencyKey={}",
                    entry.id(), entry.kind(), entry.lines().size(), idempotencyKey);
        }
    }

    // The current observation is the HTTP request's, whose span is the root of the posting's trace. A
    // high-cardinality value goes to that span only, never to a metric tag.
    private void tagRequestSpan(String entryId) {
        Observation request = observations.getCurrentObservation();
        if (request != null) {
            request.highCardinalityKeyValue(TelemetryKeys.ENTRY_ID, entryId);
        }
    }

}
