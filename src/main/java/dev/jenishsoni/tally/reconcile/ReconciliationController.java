package dev.jenishsoni.tally.reconcile;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets an operator run a recount on demand. Under /admin, so it needs the admin API key. */
@RestController
class ReconciliationController {

    private final ReconciliationJob job;

    ReconciliationController(ReconciliationJob job) {
        this.job = job;
    }

    @PostMapping("/admin/reconcile")
    ReconciliationReport reconcile() {
        return job.run();
    }

}
