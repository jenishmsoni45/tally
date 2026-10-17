package dev.jenishsoni.tally.account;

import dev.jenishsoni.tally.common.Money;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Pattern(regexp = Money.CURRENCY_PATTERN, message = "must be 3 uppercase letters") String currency,
        Boolean allowNegative) {

    // Boxed because Jackson 3 rejects a missing primitive; an omitted flag means false.
    public CreateAccountRequest {
        allowNegative = Boolean.TRUE.equals(allowNegative);
    }

}
