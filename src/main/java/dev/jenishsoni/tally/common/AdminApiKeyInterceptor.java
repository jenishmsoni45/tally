package dev.jenishsoni.tally.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Admits an admin request only with {@code Authorization: Bearer <tally.admin.api-key>}. The
 * Authorization header is the standard place for credentials, which proxies and log tools already
 * redact. Every refusal carries the same message, so a caller learns nothing about why.
 */
@Component
class AdminApiKeyInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminApiKeyInterceptor.class);

    // 32 random hex characters are 128 bits, far beyond what can be guessed over HTTP.
    static final int MIN_KEY_LENGTH = 32;

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String REFUSAL = "A valid admin API key is required";

    private final byte[] expectedKey;

    AdminApiKeyInterceptor(@Value("${tally.admin.api-key:}") String apiKey) {
        if (apiKey.isBlank()) {
            log.warn("tally.admin.api-key is not set, so every admin request is refused until it is");
            this.expectedKey = new byte[0];
        } else if (apiKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException(
                    "tally.admin.api-key must be at least " + MIN_KEY_LENGTH + " characters long");
        } else {
            this.expectedKey = apiKey.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!hasValidKey(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            throw new UnauthorizedException(REFUSAL);
        }
        return true;
    }

    private boolean hasValidKey(String authorization) {
        // An unset key must be refused here: isEqual of two empty arrays is true.
        if (expectedKey.length == 0 || authorization == null || !hasBearerScheme(authorization)) {
            return false;
        }
        byte[] providedKey = authorization.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
        // Expected key first: isEqual loops over its first argument's length (checked in the JDK 25
        // source), so the time taken does not depend on what the caller sent.
        return MessageDigest.isEqual(expectedKey, providedKey);
    }

    // Auth schemes are case-insensitive (RFC 9110, section 11.1).
    private static boolean hasBearerScheme(String authorization) {
        return authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());
    }

}
