package com.eventledger.gateway.exception;

import com.eventledger.gateway.dto.GatewayDtos;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<GatewayDtos.ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(error(400, "Validation Failed", message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<GatewayDtos.ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(error(400, "Bad Request", ex.getMessage()));
    }

    @ExceptionHandler(AccountServiceUnavailableException.class)
    public ResponseEntity<GatewayDtos.ErrorResponse> handleServiceUnavailable(AccountServiceUnavailableException ex) {
        log.warn("Account Service unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error(503, "Service Unavailable", ex.getMessage()));
    }

    @ExceptionHandler(AccountServiceException.class)
    public ResponseEntity<GatewayDtos.ErrorResponse> handleAccountServiceError(AccountServiceException ex) {
        log.error("Account Service error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(error(502, "Bad Gateway", "Account Service returned an error"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<GatewayDtos.ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error(500, "Internal Server Error", "An unexpected error occurred"));
    }

    private GatewayDtos.ErrorResponse error(int status, String error, String message) {
        return GatewayDtos.ErrorResponse.builder()
                .status(status)
                .error(error)
                .message(message)
                .timestamp(Instant.now())
                .traceId(MDC.get("traceId"))
                .build();
    }
}
