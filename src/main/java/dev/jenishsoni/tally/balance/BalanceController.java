package dev.jenishsoni.tally.balance;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

@RestController
class BalanceController {

    // The page size cap bounds one response; clients page with the cursor.
    private static final int MAX_LIMIT = 200;

    private final BalanceService service;

    BalanceController(BalanceService service) {
        this.service = service;
    }

    @GetMapping("/accounts/{id}/balance")
    BalanceResponse balance(@PathVariable UUID id) {
        return service.balanceOf(id);
    }

    @GetMapping("/accounts/{id}/entries")
    AccountLinePage entries(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @PositiveOrZero long cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_LIMIT) int limit) {
        return service.linesOf(id, cursor, limit);
    }

}
