package dev.jenishsoni.tally.risk;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stand-in for the risk engine's decision API, on the JDK's built-in HTTP server so the tests need no new
 * dependency. Each test sets how it answers, and every request it receives is kept for assertions. Handlers run on
 * virtual threads, so one request left hanging never delays another.
 */
final class FakeGuard implements AutoCloseable {

    /** How the fake answers one request; the request's body has already been read into {@code request}. */
    @FunctionalInterface
    interface Answer {
        void answer(HttpExchange exchange, Received request) throws IOException, InterruptedException;
    }

    /** A request the fake received: its JSON body and the trace header tally sent with it. */
    record Received(Map<String, Object> body, String traceparent) {

        String requestId() {
            return (String) body.get("requestId");
        }

    }

    // Longer than any test waits, so a hanging handler is always freed by reset() or close() first.
    private static final Duration HANG_LIMIT = Duration.ofSeconds(30);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Answer answer = decided(UUID.randomUUID(), "allow", "hold");
    private volatile CountDownLatch hanging = new CountDownLatch(1);
    private HttpServer server;
    private int port;

    /** Starts on a free loopback port; {@link #start()} after {@link #stop()} reuses the same port. */
    static FakeGuard started() throws IOException {
        FakeGuard guard = new FakeGuard();
        guard.bind(0);
        return guard;
    }

    String url() {
        return "http://127.0.0.1:" + port;
    }

    void answerWith(Answer next) {
        answer = next;
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    long callsFor(String requestId) {
        return received.stream().filter(request -> requestId.equals(request.requestId())).count();
    }

    /** Frees every hanging handler, forgets the requests and answers allow again, for the next test. */
    void reset() {
        hanging.countDown();
        hanging = new CountDownLatch(1);
        received.clear();
        answer = decided(UUID.randomUUID(), "allow", "hold");
    }

    /** Closes the listening socket, so a call is refused until {@link #start()}. */
    void stop() {
        server.stop(0);
    }

    void start() throws IOException {
        bind(port);
    }

    @Override
    public void close() {
        hanging.countDown();
        server.stop(0);
        handlers.close();
    }

    static Answer decided(UUID decisionId, String decision, String onTimeout) {
        return json(200, """
                {"decisionId":"%s","decision":"%s","ruleSet":"v1","fired":["test_rule"],"onTimeout":"%s"}"""
                .formatted(decisionId, decision, onTimeout));
    }

    static Answer json(int status, String body) {
        return (exchange, request) -> respond(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    /** Accepts the request and never answers, until the test ends. */
    Answer silent() {
        return (exchange, request) -> hang();
    }

    /** Waits, then answers as {@code then} does; the wait ends early when the test ends. */
    Answer after(Duration delay, Answer then) {
        return (exchange, request) -> {
            hanging.await(delay.toMillis(), TimeUnit.MILLISECONDS);
            then.answer(exchange, request);
        };
    }

    /** Sends the status and headers of a 200 at once, then never sends the body, until the test ends. */
    Answer headersOnly() {
        return (exchange, request) -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            hang();
        };
    }

    private void bind(int requestedPort) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 0);
        server.setExecutor(handlers);
        server.createContext("/v1/decisions", this::handle);
        server.start();
        port = server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Received request = new Received(readBody(exchange.getRequestBody()),
                    exchange.getRequestHeaders().getFirst("traceparent"));
            received.add(request);
            answer.answer(exchange, request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void hang() throws InterruptedException {
        hanging.await(HANG_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static Map<String, Object> readBody(InputStream body) throws IOException {
        return JSON.readValue(body.readAllBytes(), new TypeReference<>() {
        });
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

}
