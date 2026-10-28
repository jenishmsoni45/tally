package dev.jenishsoni.tally.common;

import java.net.URI;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.dao.NonTransientDataAccessResourceException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The only place errors become responses. Extending ResponseEntityExceptionHandler makes
 * Spring's own failures (malformed JSON, bad path variable, wrong method) RFC 9457 problems too.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // Relative URIs, which RFC 9457 allows. Clients such as the risk engine tell the risk outcomes apart by type,
    // since both refusals are 403. Every other problem keeps about:blank.
    private static final URI TRANSFER_HELD = URI.create("/problems/transfer-held");
    private static final URI TRANSFER_BLOCKED = URI.create("/problems/transfer-blocked");
    private static final URI RISK_UNAVAILABLE = URI.create("/problems/risk-unavailable");

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(UnprocessableException.class)
    ProblemDetail unprocessable(UnprocessableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    // The database is down, unreachable or too slow: no connection, a timeout, a lost connection, a lock or
    // serialization failure, or a commit whose outcome is unknown. Spring's DAO categories rather than single
    // exceptions, so every such failure is a 503. Logged here because, once handled, the servlet container no
    // longer logs it.
    @ExceptionHandler({ CannotCreateTransactionException.class, TransactionTimedOutException.class,
            TransactionSystemException.class, TransientDataAccessException.class,
            RecoverableDataAccessException.class, NonTransientDataAccessResourceException.class })
    ProblemDetail databaseUnavailable(RuntimeException e) {
        log.warn("Database unavailable or too slow; answering 503", e);
        // Every endpoint can answer this, and only postings take a key, so the advice names postings.
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The ledger database is unavailable or too slow; try again. A posting retried with the same"
                        + " Idempotency-Key moves money at most once.");
    }

    // The decision id lets support find the decision. The rules that fired stay with the risk engine, so nobody can
    // tune a transfer against them.
    @ExceptionHandler(TransferRefusedException.class)
    ProblemDetail transferRefused(TransferRefusedException e) {
        ProblemDetail problem = switch (e.reason()) {
            case HELD -> problem(HttpStatus.FORBIDDEN, TRANSFER_HELD, "Transfer held for review",
                    "The risk check held this transfer for review.");
            case BLOCKED -> problem(HttpStatus.FORBIDDEN, TRANSFER_BLOCKED, "Transfer blocked",
                    "The risk check blocked this transfer.");
        };
        problem.setProperty("decisionId", e.decisionId());
        return problem;
    }

    // Not logged here: the transfer path has already logged the backup answer with the key.
    @ExceptionHandler(RiskUnavailableException.class)
    ProblemDetail riskUnavailable(RiskUnavailableException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, RISK_UNAVAILABLE, "Risk check unavailable",
                "The risk check did not answer in time; try again. A transfer retried with the same Idempotency-Key"
                        + " moves money at most once.");
    }

    // RFC 9110 requires a 401 to name the scheme the client should use.
    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<ProblemDetail> unauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = e.getBody();
        problem.setProperty("errors",
                e.getBindingResult().getFieldErrors().stream().map(ApiExceptionHandler::describe).toList());
        return handleExceptionInternal(e, problem, headers, status, request);
    }

    /**
     * Spring validates the whole method, request body included, as soon as one parameter such as the
     * Idempotency-Key header has a constraint; the body's violations are reported in the same shape.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = e.getBody();
        problem.setProperty("errors",
                e.getParameterValidationResults().stream().flatMap(ApiExceptionHandler::describe).toList());
        return handleExceptionInternal(e, problem, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        return problem;
    }

    private static Stream<FieldViolation> describe(ParameterValidationResult result) {
        if (result instanceof ParameterErrors errors) {
            return errors.getFieldErrors().stream().map(ApiExceptionHandler::describe);
        }
        String name = nameOf(result.getMethodParameter());
        return result.getResolvableErrors().stream()
                .map(error -> new FieldViolation(name, error.getDefaultMessage()));
    }

    private static String nameOf(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        return header != null ? header.name() : parameter.getParameterName();
    }

    private static FieldViolation describe(FieldError error) {
        return new FieldViolation(error.getField(), error.getDefaultMessage());
    }

    record FieldViolation(String field, String message) {
    }

}
