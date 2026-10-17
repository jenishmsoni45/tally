package dev.jenishsoni.tally.posting;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
class PostingController {

    private final PostingService service;

    PostingController(PostingService service) {
        this.service = service;
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    JournalEntry transfer(@Valid @RequestBody TransferRequest request) {
        return service.postTransfer(request);
    }

    @PostMapping("/entries")
    @ResponseStatus(HttpStatus.CREATED)
    JournalEntry entry(@Valid @RequestBody EntryRequest request) {
        return service.postEntry(request);
    }

}
