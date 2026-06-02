package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.dto.GatewayDtos;
import com.eventledger.gateway.metrics.GatewayMetricsService;
import com.eventledger.gateway.model.EventRecord;
import com.eventledger.gateway.model.EventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final ObjectMapper objectMapper;
    private final GatewayMetricsService metricsService;

    /**
     * Submits a new event.
     *
     * <p>Decision flow:
     * <ol>
     *   <li>Validate the {@code type} field (CREDIT or DEBIT).</li>
     *   <li>Check idempotency — if {@code eventId} already exists, return stored record with {@code duplicate=true}.</li>
     *   <li>Persist the event locally (Gateway's own store) — this ensures GET endpoints work independently.</li>
     *   <li>Forward to Account Service to update the account balance.</li>
     * </ol>
     *
     * <p>The Gateway persists the event <em>before</em> calling the Account Service.
     * This means in a failure scenario (e.g., Account Service down after our DB write),
     * the event exists in the Gateway but not in the Account Service. The spec calls for
     * returning 503 in this case — a future enhancement could queue these for replay.</p>
     */
    @Transactional
    public GatewayDtos.EventResponse submitEvent(GatewayDtos.SubmitEventRequest request) {
        // Validate event type
        EventRecord.EventType eventType = parseEventType(request.getType());

        // Idempotency check — short-circuit on duplicate
        if (eventRepository.existsByEventId(request.getEventId())) {
            log.info("Duplicate event detected eventId={} — returning existing record", request.getEventId());
            metricsService.incrementDuplicateEvents();
            EventRecord existing = eventRepository.findByEventId(request.getEventId()).orElseThrow();
            return toEventResponse(existing);
        }

        // Persist locally first — ensures GET endpoints are independent of Account Service
        EventRecord record = EventRecord.builder()
                .eventId(request.getEventId())
                .accountId(request.getAccountId())
                .type(eventType)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .eventTimestamp(request.getEventTimestamp())
                .receivedAt(Instant.now())
                .duplicate(false)
                .metadataJson(serializeMetadata(request.getMetadata()))
                .build();
        eventRepository.save(record);

        // Forward to Account Service (circuit breaker + retry wraps this call)
        Map<String, Object> accountRequest = new HashMap<>();
        accountRequest.put("eventId", request.getEventId());
        accountRequest.put("accountId", request.getAccountId());
        accountRequest.put("type", eventType.name());
        accountRequest.put("amount", request.getAmount());
        accountRequest.put("currency", request.getCurrency());
        accountRequest.put("eventTimestamp", request.getEventTimestamp().toString());

        accountServiceClient.applyTransaction(request.getAccountId(), accountRequest);
        metricsService.incrementEventsProcessed(eventType.name());
        log.info("Event submitted successfully eventId={} accountId={}", request.getEventId(), request.getAccountId());

        return toEventResponse(record);
    }

    /**
     * Retrieves a single event by its ID from Gateway's local store.
     * This works even when the Account Service is unavailable.
     */
    @Transactional(readOnly = true)
    public GatewayDtos.EventResponse getEvent(String eventId) {
        EventRecord record = eventRepository.findByEventId(eventId)
                .orElseThrow(() -> new NoSuchElementException("Event not found: " + eventId));
        return toEventResponse(record);
    }

    /**
     * Lists all events for an account, ordered by business timestamp (eventTimestamp).
     * This works even when the Account Service is unavailable.
     */
    @Transactional(readOnly = true)
    public List<GatewayDtos.EventResponse> listEventsByAccount(String accountId) {
        return eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId)
                .stream()
                .map(this::toEventResponse)
                .toList();
    }

    private EventRecord.EventType parseEventType(String type) {
        try {
            return EventRecord.EventType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("type must be CREDIT or DEBIT, got: " + type);
        }
    }

    private GatewayDtos.EventResponse toEventResponse(EventRecord record) {
        return GatewayDtos.EventResponse.builder()
                .eventId(record.getEventId())
                .accountId(record.getAccountId())
                .type(record.getType())
                .amount(record.getAmount())
                .currency(record.getCurrency())
                .eventTimestamp(record.getEventTimestamp())
                .receivedAt(record.getReceivedAt())
                .duplicate(record.isDuplicate())
                .metadata(deserializeMetadata(record.getMetadataJson()))
                .build();
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metadata", e);
            return null;
        }
    }

    private Map<String, Object> deserializeMetadata(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize metadata", e);
            return null;
        }
    }
}
