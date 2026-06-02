package com.eventledger.account.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_txn_account_id", columnList = "account_id"),
        @Index(name = "idx_txn_event_id", columnList = "event_id", unique = true),
        @Index(name = "idx_txn_event_timestamp", columnList = "event_timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /**
     * The external eventId from the gateway — used for idempotency at the account level.
     */
    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private TransactionType type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /**
     * The business timestamp of the event — used for chronological ordering.
     * This differs from processedAt (wall-clock arrival time).
     */
    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public enum TransactionType {
        CREDIT, DEBIT
    }
}
