package com.rightpath.error;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mail.MailException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import com.rightpath.exceptions.AiServiceException;
import com.rightpath.exceptions.ApplicationDeadlinePassedException;
import com.rightpath.exceptions.JobDeadlineInPastException;
import com.rightpath.exceptions.JobPostNotFoundException;
import com.rightpath.exceptions.JobPrefixImmutableException;
import com.rightpath.exceptions.CompilerException;
import com.rightpath.exceptions.CompilerToolchainException;
import com.rightpath.exceptions.UnsupportedLanguageException;
import com.rightpath.exceptions.CustomException;
import com.rightpath.exceptions.InactiveUserException;
import com.rightpath.exceptions.InvalidAccessTokenException;
import com.rightpath.exceptions.InvalidRefreshTokenException;
import com.rightpath.exceptions.PasswordNotMatchException;
import com.rightpath.exceptions.PasswordSizeException;
import com.rightpath.exceptions.RefreshTokenExpiredException;
import com.rightpath.exceptions.RefreshTokenReuseException;
import com.rightpath.exceptions.ResourceNotFoundException;
import com.rightpath.exceptions.StorageException;
import com.rightpath.exceptions.UserAlreadyInDatabaseException;
import com.rightpath.exceptions.UserNotFoundDbException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

/**
 * Single, application-wide exception handler.
 *
 * <p>Every exception that escapes a controller is translated here into a
 * consistent {@link ApiError} body ({@code success=false}, machine-readable
 * {@code code}, human-readable {@code message}, request {@code path},
 * {@code timestamp} and optional {@code details}). Because this advice covers
 * all cases, services should simply <b>throw</b> a meaningful exception rather
 * than wrap calls in try/catch and swallow the cause.</p>
 *
 * <p>Only the fallback {@link #handleGeneric} hides its cause (to avoid leaking
 * internals); every other handler returns the exception's own message so the
 * client learns exactly what went wrong (e.g. "Email already exists",
 * "Invalid username or password", "User is Inactive").</p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.rightpath")
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static ResponseEntity<ApiError> build(HttpStatus status, String code, String message,
            HttpServletRequest req, Map<String, Object> details) {
        return ResponseEntity.status(status)
                .body(ApiError.of(code, message, req.getRequestURI(), details));
    }

    // ---------------------------------------------------------------------
    // 400 - Validation / malformed request
    // ---------------------------------------------------------------------

    /** Bean-validation failures on @Valid @RequestBody arguments (per-field detail). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, Object> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            // Keep the first message per field.
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        logger.warn("Validation failed for {}: {}", req.getRequestURI(), fieldErrors);
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR,
                "Validation failed. Please correct the highlighted fields.", req, fieldErrors);
    }

    /** Bean-validation failures on @RequestParam / @PathVariable / method params. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest req) {
        Map<String, Object> violations = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v ->
                violations.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR,
                "Validation failed. Please correct the highlighted fields.", req, violations);
    }

    /** Body could not be parsed (malformed/empty JSON, wrong types). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST,
                "Malformed request body. Please send valid JSON.", req, null);
    }

    /** Manual JSON (de)serialization failure, e.g. objectMapper.readValue on a request part. */
    @ExceptionHandler(com.fasterxml.jackson.core.JsonProcessingException.class)
    public ResponseEntity<ApiError> handleJsonProcessing(com.fasterxml.jackson.core.JsonProcessingException ex,
            HttpServletRequest req) {
        logger.warn("JSON processing error at {}: {}", req.getRequestURI(), ex.getOriginalMessage());
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST,
                "Invalid JSON payload. Please check the request format.", req, null);
    }

    /** A path/query parameter had the wrong type (e.g. "abc" for a numeric id). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        String message = "Invalid value '" + ex.getValue() + "' for parameter '" + ex.getName() + "'.";
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, message, req, null);
    }

    /** A required query parameter was absent. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest req) {
        String message = "Missing required parameter '" + ex.getParameterName() + "'.";
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, message, req, null);
    }

    /** A required multipart part (e.g. an uploaded file) was absent. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex, HttpServletRequest req) {
        String message = "Missing required file/part '" + ex.getRequestPartName() + "'.";
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, message, req, null);
    }

    /** A required request header was absent. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest req) {
        String message = "Missing required header '" + ex.getHeaderName() + "'.";
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, message, req, null);
    }

    /**
     * Generic bad-argument errors thrown by services (e.g. "Email cannot be
     * empty", "Language must not be null"). The thrown message is passed through
     * so the client sees exactly which input was wrong.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest req) {
        logger.warn("Bad request at {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, ex.getMessage(), req, null);
    }

    /**
     * Invalid-state / illegal-transition errors (e.g. "Assessment has already
     * been submitted", "Cannot transition from REJECTED"). Treated as a conflict
     * with the resource's current state.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, HttpServletRequest req) {
        logger.warn("Illegal state at {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ErrorCodes.CONFLICT, ex.getMessage(), req, null);
    }

    /** Bare {@code Optional.get()} / {@code orElseThrow()} on an absent value. */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> handleNoSuchElement(NoSuchElementException ex, HttpServletRequest req) {
        logger.warn("Missing element at {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "The requested resource was not found.", req, null);
    }

    // ---------------------------------------------------------------------
    // 401 / 403 - Authentication & authorization
    // ---------------------------------------------------------------------

    /** Wrong password (or bad principal). Kept generic to avoid user enumeration. */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCreds(BadCredentialsException ex, HttpServletRequest req) {
        logger.warn("Bad credentials for {}", req.getRequestURI());
        return build(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_UNAUTHORIZED,
                "Invalid email or password.", req, null);
    }

    /**
     * Spring wraps any non-{@code UsernameNotFoundException} thrown by
     * {@code UserDetailsService.loadUserByUsername} into an
     * {@link InternalAuthenticationServiceException}. Unwrap it so our own
     * domain exceptions (unknown user, inactive account) map to the right
     * response instead of a generic 401.
     */
    @ExceptionHandler(InternalAuthenticationServiceException.class)
    public ResponseEntity<ApiError> handleInternalAuth(InternalAuthenticationServiceException ex, HttpServletRequest req) {
        Throwable cause = ex.getCause();
        if (cause instanceof InactiveUserException) {
            logger.warn("Inactive user login at {}: {}", req.getRequestURI(), cause.getMessage());
            return build(HttpStatus.FORBIDDEN, ErrorCodes.USER_INACTIVE, cause.getMessage(), req, null);
        }
        if (cause instanceof UserNotFoundDbException) {
            // During login, do NOT reveal that the account is unknown (avoids user
            // enumeration) — return the same message as a wrong password.
            logger.warn("Login for unknown user at {}", req.getRequestURI());
            return build(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_UNAUTHORIZED,
                    "Invalid email or password.", req, null);
        }
        // A genuine internal failure during authentication (e.g. DB unavailable).
        logger.error("Internal authentication error at {}", req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR,
                "Something went wrong on our end. Please try again later.", req, null);
    }

    /** Any other authentication failure not matched above. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuth(AuthenticationException ex, HttpServletRequest req) {
        logger.warn("Authentication failed for {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_UNAUTHORIZED,
                "Invalid email or password.", req, null);
    }

    @ExceptionHandler(InvalidAccessTokenException.class)
    public ResponseEntity<ApiError> handleInvalidAccess(InvalidAccessTokenException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_INVALID_TOKEN, ex.getMessage(), req, null);
    }

    @ExceptionHandler({ InvalidRefreshTokenException.class, RefreshTokenExpiredException.class })
    public ResponseEntity<ApiError> handleInvalidRefresh(RuntimeException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_INVALID_REFRESH, ex.getMessage(), req, null);
    }

    @ExceptionHandler(RefreshTokenReuseException.class)
    public ResponseEntity<ApiError> handleReuse(RefreshTokenReuseException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ErrorCodes.AUTH_REFRESH_REUSE_DETECTED, ex.getMessage(), req,
                Map.of("sessionId", ex.getSessionId().toString()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, ErrorCodes.AUTH_FORBIDDEN,
                "You do not have permission to perform this action.", req, null);
    }

    @ExceptionHandler(InactiveUserException.class)
    public ResponseEntity<ApiError> handleInactiveUser(InactiveUserException ex, HttpServletRequest req) {
        logger.warn("Inactive user: {}", ex.getMessage());
        return build(HttpStatus.FORBIDDEN, ErrorCodes.USER_INACTIVE, ex.getMessage(), req, null);
    }

    // ---------------------------------------------------------------------
    // 404 - Not found
    // ---------------------------------------------------------------------

    @ExceptionHandler(UserNotFoundDbException.class)
    public ResponseEntity<ApiError> handleUserNotFound(UserNotFoundDbException ex, HttpServletRequest req) {
        logger.warn("User not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ErrorCodes.USER_NOT_FOUND, ex.getMessage(), req, null);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleResourceNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        logger.warn("Resource not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND, ex.getMessage(), req, null);
    }

    /** JPA entity lookups (e.g. getReferenceById / orElseThrow(EntityNotFoundException::new)). */
    @ExceptionHandler(jakarta.persistence.EntityNotFoundException.class)
    public ResponseEntity<ApiError> handleEntityNotFound(jakarta.persistence.EntityNotFoundException ex, HttpServletRequest req) {
        logger.warn("Entity not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND, ex.getMessage(), req, null);
    }

    // ---------------------------------------------------------------------
    // 400 / 409 - Domain / business rules
    // ---------------------------------------------------------------------

    @ExceptionHandler(PasswordSizeException.class)
    public ResponseEntity<ApiError> handlePasswordSize(PasswordSizeException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.PASSWORD_POLICY, ex.getMessage(), req, null);
    }

    @ExceptionHandler(PasswordNotMatchException.class)
    public ResponseEntity<ApiError> handlePasswordMismatch(PasswordNotMatchException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.PASSWORD_MISMATCH, ex.getMessage(), req, null);
    }

    @ExceptionHandler(ApplicationDeadlinePassedException.class)
    public ResponseEntity<ApiError> handleDeadlinePassed(ApplicationDeadlinePassedException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.APPLICATION_DEADLINE_PASSED, ex.getMessage(), req, null);
    }

    /** Editing a job post that does not exist. */
    @ExceptionHandler(JobPostNotFoundException.class)
    public ResponseEntity<ApiError> handleJobPostNotFound(JobPostNotFoundException ex, HttpServletRequest req) {
        logger.warn("Job post not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ErrorCodes.JOB_NOT_FOUND, ex.getMessage(), req, null);
    }

    /** Attempt to rename a job post's prefix, which every related record keys off. */
    @ExceptionHandler(JobPrefixImmutableException.class)
    public ResponseEntity<ApiError> handleJobPrefixImmutable(JobPrefixImmutableException ex, HttpServletRequest req) {
        logger.warn("Rejected job prefix change at {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.JOB_PREFIX_IMMUTABLE, ex.getMessage(), req, null);
    }

    /** Job update that moves the application deadline into the past. */
    @ExceptionHandler(JobDeadlineInPastException.class)
    public ResponseEntity<ApiError> handleJobDeadlineInPast(JobDeadlineInPastException ex, HttpServletRequest req) {
        logger.warn("Rejected past job deadline at {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.JOB_DEADLINE_IN_PAST, ex.getMessage(), req, null);
    }

    /** Duplicate registration / conflicting state (e.g. "Email already exists"). */
    @ExceptionHandler(UserAlreadyInDatabaseException.class)
    public ResponseEntity<ApiError> handleUserAlreadyExists(UserAlreadyInDatabaseException ex, HttpServletRequest req) {
        logger.warn("Duplicate user: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, ErrorCodes.USER_ALREADY_EXISTS, ex.getMessage(), req, null);
    }

    /** Generic business-rule violation. Carries the thrown message to the client. */
    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiError> handleBusiness(CustomException ex, HttpServletRequest req) {
        logger.warn("Business rule violation at {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.BUSINESS_ERROR, ex.getMessage(), req, null);
    }

    /** DB constraint breach (e.g. unique index) that reached the persistence layer. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        logger.warn("Data integrity violation at {}: {}", req.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, ErrorCodes.CONFLICT,
                "The request conflicts with existing data.", req, null);
    }

    // ---------------------------------------------------------------------
    // 405 / 413 / 415 - Transport-level
    // ---------------------------------------------------------------------

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, ErrorCodes.METHOD_NOT_ALLOWED,
                "HTTP method '" + ex.getMethod() + "' is not supported for this endpoint.", req, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest req) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCodes.UNSUPPORTED_MEDIA_TYPE,
                "Content type is not supported.", req, null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadSize(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCodes.PAYLOAD_TOO_LARGE,
                "Uploaded file is too large.", req, null);
    }

    // ---------------------------------------------------------------------
    // 5xx - Infrastructure / unexpected
    // ---------------------------------------------------------------------

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ApiError> handleStorage(StorageException ex, HttpServletRequest req) {
        logger.error("Storage error at {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.STORAGE_ERROR, ex.getMessage(), req, null);
    }

    /** File / I/O failure (non-JSON). JsonProcessingException is handled separately as 400. */
    @ExceptionHandler(java.io.IOException.class)
    public ResponseEntity<ApiError> handleIo(java.io.IOException ex, HttpServletRequest req) {
        logger.error("I/O error at {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR,
                "A file or I/O error occurred while processing your request.", req, null);
    }

    /**
     * A language we cannot run. The client picked it, so this is a 400 and the
     * message names what would have worked.
     */
    @ExceptionHandler(UnsupportedLanguageException.class)
    public ResponseEntity<ApiError> handleUnsupportedLanguage(UnsupportedLanguageException ex,
            HttpServletRequest req) {
        logger.warn("Unsupported language at {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.COMPILER_UNSUPPORTED_LANGUAGE, ex.getMessage(), req, null);
    }

    /**
     * The compiler or interpreter is missing from this server. That is an
     * operational failure, so it answers 503 — a candidate should be told to try
     * again rather than shown a compile error about code that is fine.
     */
    @ExceptionHandler(CompilerToolchainException.class)
    public ResponseEntity<ApiError> handleCompilerToolchain(CompilerToolchainException ex, HttpServletRequest req) {
        logger.error("Compiler toolchain unavailable at {}: {}", req.getRequestURI(), ex.getMessage(), ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.COMPILER_UNAVAILABLE,
                "The code execution service is temporarily unavailable. Your answer is saved — please run it again.",
                req, null);
    }

    /**
     * A submission we could not process at all — missing script or language.
     *
     * <p>Note what does <em>not</em> reach here: code that fails to compile,
     * crashes, times out or prints the wrong answer. Those are ordinary results
     * of running a candidate's code and come back 200 with a per-case status, not
     * as errors.</p>
     */
    @ExceptionHandler(CompilerException.class)
    public ResponseEntity<ApiError> handleCompiler(CompilerException ex, HttpServletRequest req) {
        logger.warn("Compiler error at {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.COMPILER_ERROR, ex.getMessage(), req, null);
    }

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ApiError> handleAiService(AiServiceException ex, HttpServletRequest req) {
        String message = ex.getMessage();
        String code;
        if (message != null && message.toLowerCase().contains("timed out")) {
            code = ErrorCodes.AI_SERVICE_TIMEOUT;
            logger.warn("AI service timeout: {} - path={}", message, req.getRequestURI());
        } else {
            code = ErrorCodes.AI_SERVICE_ERROR;
            logger.warn("AI service error: {} - path={}", message, req.getRequestURI());
        }
        return build(HttpStatus.SERVICE_UNAVAILABLE, code, message, req, null);
    }

    /**
     * An upstream HTTP call (e.g. OpenAI via WebClient) returned an error status.
     * Common causes: 401 (missing/invalid API key), 404/400 (bad model name),
     * 429 (rate limited). We surface the upstream status so the operator can act,
     * but never echo the upstream response body (may contain sensitive detail).
     */
    @ExceptionHandler(org.springframework.web.reactive.function.client.WebClientResponseException.class)
    public ResponseEntity<ApiError> handleUpstream(
            org.springframework.web.reactive.function.client.WebClientResponseException ex, HttpServletRequest req) {
        int upstreamStatus = ex.getStatusCode().value();
        logger.error("Upstream service error at {}: status={} body={}",
                req.getRequestURI(), upstreamStatus, ex.getResponseBodyAsString());

        String hint;
        if (upstreamStatus == 401 || upstreamStatus == 403) {
            hint = "the AI service rejected the credentials (check the API key)";
        } else if (upstreamStatus == 404 || upstreamStatus == 400) {
            hint = "the AI request was invalid (check the configured model/parameters)";
        } else if (upstreamStatus == 429) {
            hint = "the AI service is rate limiting requests, please try again shortly";
        } else {
            hint = "the AI service returned an error, please try again later";
        }
        return build(HttpStatus.BAD_GATEWAY, ErrorCodes.AI_SERVICE_ERROR,
                "AI service request failed (upstream status " + upstreamStatus + "): " + hint + ".", req, null);
    }

    /**
     * An upstream HTTP call never got a response — DNS resolution failure,
     * connection refused/timeout, TLS error, etc. Typically means the server has
     * no network/internet route to the AI service (rather than a code bug).
     */
    @ExceptionHandler(org.springframework.web.reactive.function.client.WebClientRequestException.class)
    public ResponseEntity<ApiError> handleUpstreamUnreachable(
            org.springframework.web.reactive.function.client.WebClientRequestException ex, HttpServletRequest req) {
        logger.error("Upstream service unreachable at {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.AI_SERVICE_ERROR,
                "Could not reach the AI service (network/DNS error). "
                        + "Please check the server's internet connectivity and try again.", req, null);
    }

    /**
     * The mail host would not take the message.
     *
     * <p>Reaches here only from the operations where sending <em>is</em> the point
     * — everything that merely notifies now treats a failed send as a reportable
     * outcome instead. A rate limit or a dropped relay is upstream and transient,
     * so it answers 502 with the host's own words rather than the generic 500
     * "Something went wrong on our end", which told the recruiter nothing about
     * whether to retry.</p>
     */
    @ExceptionHandler(MailException.class)
    public ResponseEntity<ApiError> handleMailFailure(MailException ex, HttpServletRequest req) {
        logger.error("Email delivery failed at {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_GATEWAY, ErrorCodes.EMAIL_DELIVERY_FAILED,
                "The email could not be sent: " + ex.getMessage() + " Nothing else was changed — try again shortly.",
                req, null);
    }

    /**
     * Final safety net. The real cause is logged with the full stack trace, but
     * the response deliberately hides internals to avoid leaking implementation
     * details to clients.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest req) {
        logger.error("Unhandled error at {}", req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR,
                "Something went wrong on our end. Please try again later.", req, null);
    }
}
