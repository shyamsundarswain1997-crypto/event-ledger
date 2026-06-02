package com.eventledger.account.dto;

import com.eventledger.account.model.Transaction;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AccountDtos {

    private AccountDtos() {}

    /**
     * Request payload from the Gateway when applying a transaction.
     */
    @Data
    public static class ApplyTransactionRequest {
        @NotBlank(message = "eventId is required")
        private String eventId;

        @NotBlank(message = "accountId is required")
        private String accountId;

        @NotNull(message = "type is required")
        private Transaction.TransactionType type;

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0001", message = "amount must be greater than zero")
        private BigDecimal amount;

        @NotBlank(message = "currency is required")
        @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO code")
        private String currency;

        @NotNull(message = "eventTimestamp is required")
        private Instant eventTimestamp;
    }

    /**
     * Response for a single applied transaction.
     */
    @Data
    @Builder
    public static class TransactionResponse {
        private String id;
        private String eventId;
        private String accountId;
        private Transaction.TransactionType type;
        private BigDecimal amount;
        private String currency;
        private Instant eventTimestamp;
        private Instant processedAt;
        private boolean duplicate;
    }

    /**
     * Account balance response.
     */
    @Data
    @Builder
    public static class BalanceResponse {
        private String accountId;
        private BigDecimal balance;
        private String currency;
        private Instant asOf;
    }

    /**
     * Full account details including recent transactions.
     */
    @Data
    @Builder
    public static class AccountDetailResponse {
        private String accountId;
        private BigDecimal balance;
        private String currency;
        private Instant createdAt;
        private Instant updatedAt;
        private List<TransactionResponse> recentTransactions;
    }

    /**
     * Health check response.
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HealthResponse {
        private String status;
        private String service;
        private Instant timestamp;
        private String database;
        private String details;
    }

    /**
     * Error response envelope.
     */
    @Data
    @Builder
    public static class ErrorResponse {
        private int status;
        private String error;
        private String message;
        private Instant timestamp;
        private String traceId;
    }
}
