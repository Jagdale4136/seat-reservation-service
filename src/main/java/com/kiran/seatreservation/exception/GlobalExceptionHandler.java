package com.kiran.seatreservation.exception;

import com.kiran.seatreservation.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // =========================================================
    // DOMAIN ERRORS
    // =========================================================

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(
            BusinessException ex,
            HttpServletRequest request) {

        log.warn(
                "Business error: method={} path={} status={} message={}",
                request.getMethod(),
                request.getRequestURI(),
                ex.getStatus().value(),
                ex.getMessage()
        );

        return buildResponse(
                ex.getStatus(),
                ex.getMessage(),
                request
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request) {

        log.warn(
                "Access denied: method={} path={} message={}",
                request.getMethod(),
                request.getRequestURI(),
                ex.getMessage()
        );

        return buildResponse(
                HttpStatus.FORBIDDEN,
                ex.getMessage(),
                request
        );
    }

    // =========================================================
    // BAD REQUESTS
    // =========================================================

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error ->
                        error.getField() + ": " + error.getDefaultMessage()
                )
                .collect(Collectors.joining(", "));

        log.warn(
                "Validation error: method={} path={} message={}",
                request.getMethod(),
                request.getRequestURI(),
                message
        );

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                message,
                request
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request) {

        log.warn(
                "Constraint violation: method={} path={} message={}",
                request.getMethod(),
                request.getRequestURI(),
                ex.getMessage()
        );

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                ex.getMessage(),
                request
        );
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(
            MissingRequestHeaderException ex,
            HttpServletRequest request) {

        String message = "Required header is missing: " + ex.getHeaderName();

        log.warn(
                "Bad request: method={} path={} message={}",
                request.getMethod(),
                request.getRequestURI(),
                message
        );

        return buildResponse(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {

        log.warn(
                "Unreadable body: method={} path={}",
                request.getMethod(),
                request.getRequestURI()
        );

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "Malformed or unreadable request body",
                request
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {

        String message = "Invalid value for parameter: " + ex.getName();

        log.warn(
                "Bad request: method={} path={} message={}",
                request.getMethod(),
                request.getRequestURI(),
                message
        );

        return buildResponse(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {

        return buildResponse(
                HttpStatus.METHOD_NOT_ALLOWED,
                ex.getMessage(),
                request
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(
            NoResourceFoundException ex,
            HttpServletRequest request) {

        return buildResponse(
                HttpStatus.NOT_FOUND,
                "Resource not found",
                request
        );
    }

    // =========================================================
    // CONCURRENCY / INFRASTRUCTURE
    // =========================================================

    /**
     * Lock timeout or deadlock victim. The transaction was rolled back, so
     * retrying with the same Idempotency-Key is safe.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleLockFailure(
            PessimisticLockingFailureException ex,
            HttpServletRequest request) {

        log.warn(
                "Lock failure: method={} path={} message={}",
                request.getMethod(),
                request.getRequestURI(),
                ex.getMessage()
        );

        return buildResponse(
                HttpStatus.CONFLICT,
                "The request conflicted with a concurrent request. Please retry.",
                request
        );
    }

    /**
     * Defensive: a unique-violation race that slipped past the locking.
     * Logged at ERROR so it gets investigated rather than hidden.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex,
            HttpServletRequest request) {

        log.error(
                "Data integrity violation: method={} path={}",
                request.getMethod(),
                request.getRequestURI(),
                ex
        );

        return buildResponse(
                HttpStatus.CONFLICT,
                "The request conflicted with existing data",
                request
        );
    }

    /**
     * Pool exhausted, database unreachable, query timeout and similar.
     */
    @ExceptionHandler({
            CannotCreateTransactionException.class,
            DataAccessResourceFailureException.class,
            TransientDataAccessException.class
    })
    public ResponseEntity<ErrorResponse> handleServiceUnavailable(
            Exception ex,
            HttpServletRequest request) {

        log.error(
                "Database unavailable: method={} path={}",
                request.getMethod(),
                request.getRequestURI(),
                ex
        );

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(toBody(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Service temporarily unavailable. Please retry.",
                        request
                ));
    }

    // =========================================================
    // CATCH-ALL
    // =========================================================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(
            Exception ex,
            HttpServletRequest request) {

        log.error(
                "Unexpected error: method={} path={}",
                request.getMethod(),
                request.getRequestURI(),
                ex
        );

        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred",
                request
        );
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private ResponseEntity<ErrorResponse> buildResponse(
            HttpStatus status,
            String message,
            HttpServletRequest request) {

        return ResponseEntity
                .status(status)
                .body(toBody(status, message, request));
    }

    private ErrorResponse toBody(
            HttpStatus status,
            String message,
            HttpServletRequest request) {

        return ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .build();
    }
}