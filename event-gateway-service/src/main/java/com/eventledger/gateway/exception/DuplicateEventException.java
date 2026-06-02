package com.eventledger.gateway.exception;

public class DuplicateEventException extends RuntimeException {
    private final String eventId;

    public DuplicateEventException(String eventId) {
        super("Event already processed: " + eventId);
        this.eventId = eventId;
    }

    public String getEventId() {
        return eventId;
    }
}
