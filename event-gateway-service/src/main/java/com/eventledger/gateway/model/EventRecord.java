package com.eventledger.gateway.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Persists every received event in the Gateway's own store.
 *
 * <p>This serves two purposes:
 * <ol>
 *   <li>Idempotency enforcement — check before forwarding to Account Service.</li>
 *   <li>Read availability — GET endpoints work even when Account Service is unavailable.</li>
 * </ol>
 */
@Entity
@Table(name = "events", indexes = {
        @Index(name = "idx_event_id", columnList = "event_id", unique = true),
        @Index(name = "idx_event_account_id", columnList = "account_id"),
        @Index(name = "idx_event_timestamp", columnList = "event_timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventRecord {

    @Id
    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private EventType type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /**
     * Business timestamp — when the event originally occurred upstream.
     * Used for chronological ordering in GET /events?account=...
     */
    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    /** When the Gateway first received this event (wall-clock arrival time). */
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    /** Whether this was a duplicate submission (same eventId seen before). */
    @Column(name = "duplicate", nullable = false)
    private boolean duplicate;

    /** Serialized as JSON text for portability with H2. */
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadataJson;

    public enum EventType {
        CREDIT, DEBIT
    }
}
