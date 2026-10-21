package dev.jenishsoni.tally.common;

/**
 * Names shared by log context (MDC) fields and span attributes, so one entry can be searched by the
 * same key in logs and in traces.
 */
public final class TelemetryKeys {

    public static final String ENTRY_ID = "entry_id";

    public static final String IDEMPOTENCY_KEY = "idempotency_key";

    private TelemetryKeys() {
    }

}
