package dev.jenishsoni.tally.posting;

import org.springframework.stereotype.Service;

@Service
class PostingServiceImpl implements PostingService {

    @Override
    public JournalEntry postTransfer(TransferRequest request) {
        throw new UnsupportedOperationException("Posting is implemented in Phase 2B");
    }

    @Override
    public JournalEntry postEntry(EntryRequest request) {
        throw new UnsupportedOperationException("Posting is implemented in Phase 2B");
    }

}
