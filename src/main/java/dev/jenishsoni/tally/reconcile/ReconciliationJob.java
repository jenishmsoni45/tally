package dev.jenishsoni.tally.reconcile;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Recounts every account and records the ones whose stored balance differs from the sum of their
 * lines. It reports drift and never edits a balance: a mismatch is a bug to investigate, and an
 * automatic fix would hide it.
 */
@Service
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    static final String DRIFT_GAUGE = "ledger.reconciliation.drift.accounts";

    // Starting value, not a measurement: unlike a posting (5 s default), the recount reads every
    // journal line, and it is still bounded so a stuck run gives back its pooled connection.
    // Phase 8 measures it and may change it.
    private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(30);

    private final ReconciliationRepository repository;
    private final TransactionTemplate transaction;
    private final MeterRegistry registry;
    private final AtomicInteger lastDrift = new AtomicInteger();
    private final AtomicBoolean gaugeRegistered = new AtomicBoolean();

    ReconciliationJob(ReconciliationRepository repository, PlatformTransactionManager transactions,
            MeterRegistry registry) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout((int) TRANSACTION_TIMEOUT.toSeconds());
        this.registry = registry;
    }

    public ReconciliationReport run() {
        List<AccountDrift> drift = transaction.execute(_ -> repository.recordDrift());
        publish(drift.size());
        logResult(drift);
        return new ReconciliationReport(drift.size(), drift);
    }

    // Registered on the first run rather than at startup, so the gauge has no value until a recount
    // has happened instead of a misleading 0. Only once: Micrometer warns about a repeated registration.
    private void publish(int driftedAccounts) {
        lastDrift.set(driftedAccounts);
        if (gaugeRegistered.compareAndSet(false, true)) {
            Gauge.builder(DRIFT_GAUGE, lastDrift, AtomicInteger::get)
                    .description("Accounts whose stored balance differed from the sum of their lines in the last recount")
                    .register(registry);
        }
    }

    private static void logResult(List<AccountDrift> drift) {
        if (drift.isEmpty()) {
            log.info("Reconciliation found no drift");
            return;
        }
        log.warn("Reconciliation found drift in {} accounts: {}", drift.size(),
                drift.stream().map(AccountDrift::accountId).toList());
    }

}
