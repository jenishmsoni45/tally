package dev.jenishsoni.tally.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Fingerprint of a request, so a key reused for a different request can be told apart from a retry. */
public final class RequestHash {

    private RequestHash() {
    }

    /**
     * SHA-256, as hex, of the operation and the canonical JSON of its request. The operation is part
     * of the hash so one key cannot be replayed across endpoints. The JSON is the bound request
     * serialized again, not the raw body: requests the server reads identically (field order, an absent
     * or null field, offset spelling) are the same request. Changing a request record's serialization
     * therefore changes the hash of keys already stored.
     */
    public static String of(String operation, String canonicalJson) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest((operation + '\n' + canonicalJson).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform must provide SHA-256.
            throw new IllegalStateException(e);
        }
    }

}
