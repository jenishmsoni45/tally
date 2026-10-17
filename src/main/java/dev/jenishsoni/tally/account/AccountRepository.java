package dev.jenishsoni.tally.account;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class AccountRepository {

    private static final RowMapper<Account> ACCOUNT_MAPPER = (rs, row) -> new Account(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("currency"),
            rs.getBoolean("allow_negative"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Account insert(UUID id, CreateAccountRequest request) {
        return jdbc.sql("""
                insert into accounts (id, code, currency, allow_negative)
                values (:id, :code, :currency, :allowNegative)
                returning id, code, currency, allow_negative, created_at
                """)
                .param("id", id)
                .param("code", request.code())
                .param("currency", request.currency())
                .param("allowNegative", request.allowNegative())
                .query(ACCOUNT_MAPPER)
                .single();
    }

    void insertZeroBalance(UUID accountId) {
        jdbc.sql("insert into balances (account_id) values (:accountId)")
                .param("accountId", accountId)
                .update();
    }

    Optional<Account> findById(UUID id) {
        return jdbc.sql("""
                select id, code, currency, allow_negative, created_at
                from accounts
                where id = :id
                """)
                .param("id", id)
                .query(ACCOUNT_MAPPER)
                .optional();
    }

}
