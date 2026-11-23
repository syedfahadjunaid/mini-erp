package com.minierp.shared;

import java.util.List;

/**
 * The exceptions services throw for expected failures. {@link GlobalExceptionHandler} turns
 * each into an RFC 9457 problem response with a deliberate status code:
 *
 * <ul>
 *   <li>404 {@link NotFoundException}: no such thing;</li>
 *   <li>403 {@link ForbiddenException}: you may see it but not do this (e.g. approve your own order);</li>
 *   <li>409 {@link ConflictException}: the current state forbids it (e.g. ship a cancelled order),
 *       or someone else changed it at the same time; retrying later may work;</li>
 *   <li>422 {@link BusinessRuleException}: the request is well-formed but a business rule says no
 *       (not enough stock, receiving more than was ordered), with a machine-readable {@code code}
 *       and, where it fits, per-field {@code errors[]}.</li>
 * </ul>
 *
 * <p>All are unchecked, so a {@code @Transactional} method that throws one rolls back.
 */
public final class Errors {

    private Errors() {}

    /** One entry of {@code errors[]}: which input is wrong, and why. */
    public record FieldProblem(String field, String message) {}

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String what, Object id) {
            super(what + " " + id + " not found.");
        }
    }

    public static class ForbiddenException extends RuntimeException {
        private final String code;

        public ForbiddenException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static class ConflictException extends RuntimeException {
        private final String code;

        public ConflictException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static class BusinessRuleException extends RuntimeException {
        private final String code;
        private final List<FieldProblem> problems;

        public BusinessRuleException(String code, String message) {
            this(code, message, List.of());
        }

        public BusinessRuleException(String code, String message, List<FieldProblem> problems) {
            super(message);
            this.code = code;
            this.problems = List.copyOf(problems);
        }

        /** A rule about one input field, e.g. {@code lines[2].quantity}. */
        public static BusinessRuleException forField(String code, String field, String message) {
            return new BusinessRuleException(code, message, List.of(new FieldProblem(field, message)));
        }

        public String code() {
            return code;
        }

        public List<FieldProblem> problems() {
            return problems;
        }
    }

    /** A document can't make this move from its current status (state machine says no): 409. */
    public static class InvalidTransitionException extends ConflictException {
        public InvalidTransitionException(String document, Object status, Object action) {
            super("invalid_transition", "A " + document + " in status " + status + " can't " + action + ".");
        }
    }
}
