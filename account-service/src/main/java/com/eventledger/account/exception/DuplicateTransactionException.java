package com.eventledger.account.exception;

public class DuplicateTransactionException extends RuntimeException {
    private final String eventId;

    public DuplicateTransactionException(String eventId) {
        super("Transaction already processed: " + eventId);
        this.eventId = eventId;
    }

    public String getEventId() {
        return eventId;
    }
}
