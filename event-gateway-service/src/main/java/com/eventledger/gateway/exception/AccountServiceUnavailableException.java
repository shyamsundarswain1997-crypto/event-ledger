package com.eventledger.gateway.exception;

/**
 * Thrown when the Account Service is unreachable, timed out, or the circuit breaker is open.
 * The Gateway maps this to a 503 Service Unavailable response.
 */
public class AccountServiceUnavailableException extends RuntimeException {
    public AccountServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public AccountServiceUnavailableException(String message) {
        super(message);
    }
}
