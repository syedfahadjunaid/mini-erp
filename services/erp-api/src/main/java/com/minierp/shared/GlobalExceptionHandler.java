package com.minierp.shared;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into RFC 9457 "problem+json" responses (status codes: see {@link Errors}).
 *
 * <p>Security rule: never send stack traces, SQL or internal names to the client. Unexpected
 * database errors are logged here and answered with a short generic message.
 *
 * <p>Authentication (401) and role (403) failures never reach this class: Spring Security
 * answers those itself.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Bean Validation failures: {@code "errors": [{"field": "lines[0].quantity", "message": "must be greater than 0"}]}. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setDetail("Some fields are invalid.");
        problem.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new Errors.FieldProblem(error.getField(),
                        Objects.requireNonNullElse(error.getDefaultMessage(), "is invalid")))
                .toList());
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @ExceptionHandler(Errors.NotFoundException.class)
    ProblemDetail notFound(Errors.NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(Errors.ForbiddenException.class)
    ProblemDetail forbidden(Errors.ForbiddenException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        problem.setProperty("code", e.code());
        return problem;
    }

    @ExceptionHandler(Errors.ConflictException.class)
    ProblemDetail conflict(Errors.ConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("code", e.code());
        return problem;
    }

    @ExceptionHandler(Errors.BusinessRuleException.class)
    ProblemDetail businessRule(Errors.BusinessRuleException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problem.setProperty("code", e.code());
        if (!e.problems().isEmpty()) {
            problem.setProperty("errors", e.problems());
        }
        if (e instanceof InsufficientStockException stock) {
            problem.setProperty("shortages", stock.shortages());
        }
        return problem;
    }

    /**
     * Optimistic-lock failure (a stale {@code version}), lock timeout or deadlock. The
     * transaction rolled back, so nothing was written and the client can reload and retry.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    ProblemDetail concurrentUpdate(ConcurrencyFailureException e) {
        log.info("Concurrency failure: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Someone else changed the same data at the same time. Reload and try again.");
        problem.setProperty("code", "concurrent_update");
        return problem;
    }

    /** A unique or foreign key constraint caught something the service didn't check first. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrityViolation(DataIntegrityViolationException e) {
        log.warn("Integrity violation: {}", e.getMostSpecificCause().getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The request conflicts with existing data.");
        problem.setProperty("code", "integrity_violation");
        return problem;
    }
}
