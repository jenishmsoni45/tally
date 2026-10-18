package dev.jenishsoni.tally.posting;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@RestController
class PostingController {

    // Printable ASCII without spaces, so a key is safe to log and to compare. 255 matches common client libraries.
    private static final String KEY_PATTERN = "[\\x21-\\x7E]{1,255}";

    private final PostingService service;

    PostingController(PostingService service) {
        this.service = service;
    }

    @PostMapping("/transfers")
    ResponseEntity<JournalEntry> transfer(@RequestHeader("Idempotency-Key") @NotBlank @Pattern(regexp = KEY_PATTERN) String key,
            @Valid @RequestBody TransferRequest request) {
        return respond(service.postTransfer(key, request));
    }

    @PostMapping("/entries")
    ResponseEntity<JournalEntry> entry(@RequestHeader("Idempotency-Key") @NotBlank @Pattern(regexp = KEY_PATTERN) String key,
            @Valid @RequestBody EntryRequest request) {
        return respond(service.postEntry(key, request));
    }

    @PostMapping("/entries/{id}/reverse")
    ResponseEntity<JournalEntry> reverse(@RequestHeader("Idempotency-Key") @NotBlank @Pattern(regexp = KEY_PATTERN) String key,
            @PathVariable UUID id) {
        return respond(service.reverseEntry(key, id));
    }

    private static ResponseEntity<JournalEntry> respond(PostingResult result) {
        return ResponseEntity.status(result.status()).body(result.entry());
    }

}
