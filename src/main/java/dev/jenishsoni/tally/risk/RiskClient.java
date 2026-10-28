package dev.jenishsoni.tally.risk;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import dev.jenishsoni.tally.risk.RiskResult.NoDecision;
import dev.jenishsoni.tally.risk.RiskResult.NoDecision.Reason;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks the risk engine whether a transfer may go ahead, with POST {tally.risk.url}/v1/decisions
 * (docs/risk-decision-call.md, 3.1 and 3.2). A call ends within tally.risk.timeout plus scheduling slack, and
 * nothing the risk engine does makes it throw: one that is slow, silent, refusing or answering nonsense gives a
 * {@link NoDecision}, for which the caller uses {@link #backupAnswer()}. This class logs nothing, so no request or
 * response body can reach the logs; the caller logs the outcome.
 */
@Component
public class RiskClient {

    private static final String DECISIONS_PATH = "/v1/decisions";

    private static final int OK = 200;
    private static final int CONFLICT = 409;

    // A valid reply is about 200 bytes plus the fired rule names. The cap stops a broken risk engine from making
    // tally buffer an unbounded body; a longer one is no decision.
    private static final int MAX_REPLY_BYTES = 16 * 1024;

    private final RiskProperties properties;
    private final JsonMapper json;
    private final AtomicReference<Decision> backupAnswer;
    // Null while the check is off: its settings are then not checked, so nothing is built from them.
    private final RestClient riskEngine;

    RiskClient(RiskProperties properties, RestClient.Builder restClients, JsonMapper json) {
        this.properties = properties;
        this.json = json;
        this.backupAnswer = new AtomicReference<>(properties.onTimeoutDefault());
        this.riskEngine = properties.enabled() ? riskEngineClient(restClients, properties) : null;
    }

    /** Whether tally.risk.enabled is on. While it is off, {@link #decide} must not be called. */
    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * The onTimeout of the latest valid reply, or tally.risk.on-timeout-default before the first one and after a
     * restart (docs/risk-decision-call.md, 3.4).
     */
    public Decision backupAnswer() {
        return backupAnswer.get();
    }

    /**
     * Asks the risk engine about one transfer. requestId is the transfer's Idempotency-Key exactly as received, and
     * amount is the positive amount in cents.
     */
    public RiskResult decide(String requestId, UUID fromAccountId, UUID toAccountId, long amount, String currency) {
        if (riskEngine == null) {
            throw new IllegalStateException("The risk check is off (tally.risk.enabled), so nothing may ask it");
        }
        // Serialized here rather than by RestClient's JSON converter, which streams the body chunked: a body with
        // a Content-Length is one every server reads, Python's built-in http.server included.
        byte[] body = json.writeValueAsBytes(
                new DecisionRequest(requestId, fromAccountId, toAccountId, amount, currency, Instant.now()));
        long startedAt = System.nanoTime();
        try {
            return riskEngine.post()
                    .uri(DECISIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((_, response) -> resultOf(response));
        } catch (ResourceAccessException _) {
            // Every I/O failure lands here, from connecting to reading the body. The client span has recorded the
            // exception, and the caller logs the outcome.
            return new NoDecision(timedOut(startedAt) ? Reason.TIMEOUT : Reason.UNAVAILABLE);
        }
    }

    // Runs inside the exchange, so an IOException while reading the body reaches decide as the same
    // ResourceAccessException as one from connecting or waiting for the headers.
    private RiskResult resultOf(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status == CONFLICT) {
            return new RiskResult.Conflict();
        }
        if (status != OK) {
            return new NoDecision(Reason.UNAVAILABLE);
        }
        return decidedOrInvalid(response.getBody().readNBytes(MAX_REPLY_BYTES + 1));
    }

    // The body is parsed only once it is all in, so a parse failure always means a bad body, never a slow one.
    private RiskResult decidedOrInvalid(byte[] body) {
        Optional<RiskResult.Decided> decided = parsed(body);
        if (decided.isEmpty()) {
            return new NoDecision(Reason.INVALID);
        }
        backupAnswer.set(decided.get().onTimeout());
        return decided.get();
    }

    private Optional<RiskResult.Decided> parsed(byte[] body) {
        if (body.length > MAX_REPLY_BYTES) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(json.readValue(body, DecisionReply.class)).flatMap(DecisionReply::toDecided);
        } catch (JacksonException _) {
            // Not JSON, or a field of the wrong JSON type: no decision, like any other body that breaks 3.2.
            return Optional.empty();
        }
    }

    // Decided by the clock, not by the exception's type. Every deadline here fires only once the timeout has
    // passed since startedAt, but each reaches decide as a different exception: HttpConnectTimeoutException while
    // connecting, HttpTimeoutException while waiting for the headers, and a plain IOException ("closed") when the
    // deadline closes a body still being read. A failure before the timeout is a refused, reset or broken connection.
    private boolean timedOut(long startedAt) {
        return System.nanoTime() - startedAt >= deadline(properties).toNanos();
    }

    // Spring's deadline counts whole milliseconds (Duration.toMillis), so timedOut must use the same value.
    private static Duration deadline(RiskProperties properties) {
        return Duration.ofMillis(properties.timeout().toMillis());
    }

    // From Spring Boot's builder, which carries the observation: each call gets a client span and sends the W3C
    // traceparent header, so the risk engine can continue the trace.
    private static RestClient riskEngineClient(RestClient.Builder restClients, RiskProperties properties) {
        return restClients
                .baseUrl(properties.url())
                .requestFactory(requestFactory(deadline(properties)))
                .build();
    }

    // Spring's JDK request factory starts its read timeout when it sends the request, and when it fires it cancels
    // the exchange, connecting included, or, once the headers are in, closes the body stream. That one value is the
    // deadline for the whole call, so it gets the whole timeout. The JDK's own connect timeout, unlimited unless set,
    // gets the same value: the deadline already covers connecting, and an equal value never ends a call sooner.
    private static ClientHttpRequestFactory requestFactory(Duration deadline) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(deadline)
                .withReadTimeout(deadline)
                // Any status but 200 and 409 is no decision (3.2); following a redirect would spend the timeout on
                // a second request instead.
                .withRedirects(HttpRedirects.DONT_FOLLOW);
        return ClientHttpRequestFactoryBuilder.jdk()
                // What Spring Boot gives its own JDK clients when virtual threads are on, as they are here. Without
                // an executor, Spring starts a new platform thread to write each request body.
                .withExecutor(new VirtualThreadTaskExecutor("risk-client-"))
                // The risk engine speaks plain HTTP/1.1, and the JDK's default, HTTP/2, adds an h2c upgrade to the
                // first request on each new connection. Keep-alive stays on, the JDK's default, so calls reuse
                // connections.
                .withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1))
                // Replies are a few hundred bytes, so compression saves nothing. Without it the deadline closes the
                // JDK's body stream itself, not a gzip stream around it, whose close can fail a racing read with a
                // NullPointerException instead of an IOException.
                .withCustomizer(factory -> factory.enableCompression(false))
                .build(settings);
    }

}
