package dev.jenishsoni.tally.account;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    /** All of them in one query. An unknown id is a 404 naming the first unknown one in the list's order. */
    public Map<UUID, Account> getAll(List<UUID> ids) {
        Map<UUID, Account> found = repository.findAllById(ids).stream()
                .collect(Collectors.toMap(Account::id, Function.identity()));
        ids.stream()
                .filter(id -> !found.containsKey(id))
                .findFirst()
                .ifPresent(id -> {
                    throw new NotFoundException("Account " + id + " not found");
                });
        return found;
    }

}
