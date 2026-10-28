package dev.jenishsoni.tally.posting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.jenishsoni.tally.common.ConflictException;
import dev.jenishsoni.tally.common.RiskUnavailableException;
import dev.jenishsoni.tally.common.TransferRefusedException;
import dev.jenishsoni.tally.idempotency.IdempotencyStore;
import dev.jenishsoni.tally.risk.Decision;
import dev.jenishsoni.tally.risk.RiskClient;
import dev.jenishsoni.tally.risk.RiskResult;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Asks the risk engine about a transfer before its posting transaction opens (docs/risk-decision-call.md, 4.1). Not
 * transactional on purpose, and a different bean from PostingService: no transaction, row lock or pooled connection
 * is held while the risk engine answers.
 */
@Component
class TransferOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TransferOrchestrator.class);

    private static final String RISK_DURATION = "ledger.risk.duration";
    private static final String OUTCOME = "outcome";

    private final PostingService posting;
    private final IdempotencyStore idempotency;
    private final RiskClient risk;
    private final MeterRegistry registry;
    private final Meter.MeterProvider<Timer> riskDurations;

    TransferOrchestrator(PostingService posting, IdempotencyStore idempotency, RiskClient risk,
            MeterRegistry registry) {
        this.posting = posting;
        this.idempotency = idempotency;
        this.risk = risk;
        this.registry = registry;
        this.riskDurations = Timer.builder(RISK_DURATION)
                .description("Time to get the risk engine's answer for a transfer, by outcome")
                .withRegistry(registry);
    }

    PostingResult postTransfer(String idempotencyKey, TransferRequest request) {
        // Off, tally behaves exactly as before. A saved response is replayed (or is a key conflict) without asking
        // again, since the risk engine already answered the request that saved it.
        if (!risk.enabled() || idempotency.hasSavedResponse(idempotencyKey)) {
            return posting.postTransfer(idempotencyKey, request);
        }
        return switch (decide(idempotencyKey, request)) {
            case RiskResult.Decided decided -> act(idempotencyKey, request, decided);
            // The risk engine's 409 means what tally's own key conflict means: one key, one request.
            case RiskResult.Conflict _ -> throw new ConflictException(PostingServiceImpl.KEY_REUSED);
            case RiskResult.NoDecision none -> backup(idempotencyKey, request, none);
        };
    }

    private RiskResult decide(String idempotencyKey, TransferRequest request) {
        Timer.Sample sample = Timer.start(registry);
        RiskResult result = risk.decide(idempotencyKey, request.fromAccountId(), request.toAccountId(),
                request.amount(), request.currency());
        sample.stop(riskDurations.withTag(OUTCOME, result.outcome()));
        return result;
    }

    private PostingResult act(String idempotencyKey, TransferRequest request, RiskResult.Decided decided) {
        return switch (decided.decision()) {
            case ALLOW -> posting.postTransfer(idempotencyKey, request, EntryRisk.decided(decided.decisionId()));
            case HOLD -> throw new TransferRefusedException(TransferRefusedException.Reason.HELD,
                    decided.decisionId());
            case BLOCK -> throw new TransferRefusedException(TransferRefusedException.Reason.BLOCKED,
                    decided.decisionId());
        };
    }

    // A backup refusal is a 503 rather than a 403: nothing was decided, and a retry with the same key asks again.
    private PostingResult backup(String idempotencyKey, TransferRequest request, RiskResult.NoDecision none) {
        Decision answer = risk.backupAnswer();
        log.warn("Risk check gave no decision, outcome={}; using the backup answer {} idempotencyKey={}",
                none.outcome(), answer.wireName(), idempotencyKey);
        if (answer == Decision.ALLOW) {
            return posting.postTransfer(idempotencyKey, request, EntryRisk.FALLBACK);
        }
        throw new RiskUnavailableException();
    }

}
