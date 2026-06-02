package com.eventledger.gateway.exception;

/**
 * Thrown when the Account Service returns an unexpected non-transient error.
 */
public class AccountServiceException extends RuntimeException {
    public AccountServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
