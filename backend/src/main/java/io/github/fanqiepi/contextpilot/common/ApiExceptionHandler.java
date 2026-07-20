package io.github.fanqiepi.contextpilot.common;

import java.util.Objects;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(
            BadRequestException exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, exception.getCode(), exception.getMessage(), request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            ResourceNotFoundException exception,
            HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, exception.getCode(), exception.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(
            ConflictException exception,
            HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, exception.getCode(), exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        FieldError error = exception.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String message = error == null
                ? "Request validation failed"
                : error.getField() + ": " + Objects.requireNonNullElse(error.getDefaultMessage(), "invalid value");
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiErrorResponse> handleMalformedRequest(
            Exception exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request body or parameter is invalid", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        String requestId = requestId(request);
        LOGGER.error("Unhandled request error, requestId={}", requestId, exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse("INTERNAL_ERROR", "An unexpected error occurred", requestId));
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(code, message, requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? UUID.randomUUID().toString() : requestId.toString();
    }
}
