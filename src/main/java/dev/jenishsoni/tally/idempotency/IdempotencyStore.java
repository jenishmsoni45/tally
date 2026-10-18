package dev.jenishsoni.tally.idempotency;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes a retried request return its first response instead of repeating the work. Both methods run
 * inside the caller's transaction, so a request that fails and rolls back leaves no key behind and
 * can be retried with the same key.
 */
@Component
public class IdempotencyStore {

    private final JdbcClient jdbc;

    IdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the key for this request. If another transaction holds the same key uncommitted, the
     * insert waits for it to end; when it commits, this call finds the saved response and replays it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Claim claim(String key, String requestHash) {
        int inserted = jdbc.sql("""
                insert into idempotency_keys (key, request_hash)
                values (:key, :requestHash)
                on conflict (key) do nothing
                """)
                .param("key", key)
                .param("requestHash", requestHash)
                .update();
        if (inserted == 1) {
            return new Claim.Claimed();
        }
        return jdbc.sql("""
                select request_hash, response_status, cast(response_body as text) as response_body
                from idempotency_keys
                where key = :key
                """)
                .param("key", key)
                .query((rs, row) -> existingClaim(key, requestHash, rs.getString("request_hash"),
                        rs.getObject("response_status", Integer.class), rs.getString("response_body")))
                .single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveResponse(String key, int status, String body) {
        int updated = jdbc.sql("""
                update idempotency_keys
                set response_status = :status, response_body = cast(:body as jsonb)
                where key = :key
                """)
                .param("status", status)
                .param("body", body)
                .param("key", key)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Idempotency key " + key + " was not claimed in this transaction");
        }
    }

    private static Claim existingClaim(String key, String requestHash, String storedHash, Integer status,
            String body) {
        if (!storedHash.equals(requestHash)) {
            return new Claim.Conflict();
        }
        if (status == null || body == null) {
            // Rows become visible only at commit, together with their response; this is corrupt data.
            throw new IllegalStateException("Idempotency key " + key + " has no saved response");
        }
        return new Claim.Replay(status, body);
    }

}
