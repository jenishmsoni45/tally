package dev.jenishsoni.tally.account;

import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.jenishsoni.tally.common.ConflictException;
import dev.jenishsoni.tally.common.NotFoundException;

@Service
public class AccountService {

    private final AccountRepository repository;

    AccountService(AccountRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Account create(CreateAccountRequest request) {
        UUID id = UUID.randomUUID();
        Account account;
        try {
            account = repository.insert(id, request);
        } catch (DuplicateKeyException e) {
            // The unique index decides, so two concurrent creates cannot both succeed.
            throw new ConflictException("An account with code '" + request.code() + "' already exists");
        }
        repository.insertZeroBalance(id);
        return account;
    }

    public Account get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Account " + id + " not found"));
    }

}
