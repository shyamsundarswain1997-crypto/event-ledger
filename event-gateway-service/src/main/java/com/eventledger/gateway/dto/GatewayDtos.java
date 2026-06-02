package com.eventledger.gateway.dto;

import com.eventledger.gateway.model.EventRecord;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public final class GatewayDtos {

    private GatewayDtos() {}

    /**
     * Incoming event submission payload from external clients.
     */
    @Data
    public static class SubmitEventRequest {

        @NotBlank(message = "eventId is required")
        private String eventId;

        @NotBlank(message = "accountId is required")
        private String accountId;

        @NotBlank(message = "type is required")
        private String type;  // Validated as CREDIT/DEBIT in service layer

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0001", inclusive = true, message = "amount must be greater than zero")
        private BigDecimal amount;

        @NotBlank(message = "currency is required")
        @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO code")
        private String currency;

        @NotNull(message = "eventTimestamp is required")
        private Instant eventTimestamp;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Map<String, Object> metadata;
    }

    /**
     * Response for a single event — returned for both POST and GET requests.
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EventResponse {
        private String eventId;
        private String accountId;
        private EventRecord.EventType type;
        private BigDecimal amount;
        private String currency;
        private Instant eventTimestamp;
        private Instant receivedAt;
        private boolean duplicate;
        private Map<String, Object> metadata;
    }

    /**
     * Health check response — includes downstream dependency status.
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HealthResponse {
        private String status;
        private String service;
        private Instant timestamp;
        private String database;
        private String accountService;
        private String details;
    }

    /**
     * Standard error envelope returned for all 4xx/5xx responses.
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
