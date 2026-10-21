package dev.jenishsoni.tally.outbox;

import java.time.Duration;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Outbox health, so a stuck relay shows on the dashboard: how many rows wait to be published and how long
 * the oldest has waited. Each value is one query over the unpublished rows, the ones the relay's partial
 * index holds, run whenever metrics are exported. A failing query is not caught here: Micrometer logs it
 * and reports NaN, which is the honest reading for "unknown".
 */
@Component
class OutboxMetrics implements MeterBinder {

    // A gauge must not hold a pooled connection while the database is slow; postings need it more.
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2);

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    OutboxMetrics(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setReadOnly(true);
        this.transaction.setTimeout((int) QUERY_TIMEOUT.toSeconds());
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("ledger.outbox.pending", this::pending)
                .description("Outbox rows not yet published to Kafka")
                .register(registry);
        Gauge.builder("ledger.outbox.oldest.age.seconds", this::oldestAgeSeconds)
                .description("Seconds since the oldest unpublished outbox row was written, 0 when there is none")
                .register(registry);
    }

    private Long pending() {
        return transaction.execute(_ -> jdbc.sql("select count(*) from outbox where published_at is null")
                .query(Long.class)
                .single());
    }

    private Double oldestAgeSeconds() {
        return transaction.execute(_ -> jdbc.sql("""
                select coalesce(extract(epoch from now() - min(created_at)), 0)
                from outbox
                where published_at is null
                """)
                .query(Double.class)
                .single());
    }

}
