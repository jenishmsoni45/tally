package dev.jenishsoni.tally.reconcile;

import java.util.List;

/** The result of one recount: every drifted account in account id order, empty when the ledger agrees. */
public record ReconciliationReport(int driftedAccounts, List<AccountDrift> drift) {

    public ReconciliationReport {
        drift = List.copyOf(drift);
    }

}
