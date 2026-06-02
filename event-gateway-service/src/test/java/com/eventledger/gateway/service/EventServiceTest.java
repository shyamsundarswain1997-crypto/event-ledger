package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.dto.GatewayDtos;
import com.eventledger.gateway.exception.AccountServiceException;
import com.eventledger.gateway.exception.AccountServiceUnavailableException;
import com.eventledger.gateway.metrics.GatewayMetricsService;
import com.eventledger.gateway.model.EventRecord;
import com.eventledger.gateway.model.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EventService Unit Tests")
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private AccountServiceClient accountServiceClient;

    @Mock
    private GatewayMetricsService metricsService;

    private EventService eventService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Use real ObjectMapper for proper JSON serialization testing
        this.objectMapper = new ObjectMapper();
        // Manually construct EventService with all dependencies
        this.eventService = new EventService(
                eventRepository,
                accountServiceClient,
                objectMapper,
                metricsService
        );
    }

    @Nested
    @DisplayName("submitEvent() tests")
    class SubmitEventTests {

        @Test
        @DisplayName("should successfully submit a CREDIT event")
        void submitEvent_SuccessfulCreditEvent() {
            // Arrange
            String eventId = "evt-123";
            String accountId = "acc-456";
            BigDecimal amount = new BigDecimal("100.00");
            Instant eventTimestamp = Instant.parse("2024-01-15T10:30:00Z");
            Map<String, Object> metadata = Map.of("reference", "REF-001");

            GatewayDtos.SubmitEventRequest request = new GatewayDtos.SubmitEventRequest();
            request.setEventId(eventId);
            request.setAccountId(accountId);
            request.setType("CREDIT");
            request.setAmount(amount);
            request.setCurrency("USD");
            request.setEventTimestamp(eventTimestamp);
            request.setMetadata(metadata);

            when(eventRepository.existsByEventId(eventId)).thenReturn(false);
            when(eventRepository.save(any(EventRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountServiceClient.applyTransaction(eq(accountId), anyMap())).thenReturn(Map.of());

            // Act
            GatewayDtos.EventResponse response = eventService.submitEvent(request);

            // Assert
            assertThat(response)
                    .isNotNull()
                    .satisfies(r -> {
                        assertThat(r.getEventId()).isEqualTo(eventId);
                        assertThat(r.getAccountId()).isEqualTo(accountId);
                        assertThat(r.getType()).isEqualTo(EventRecord.EventType.CREDIT);
                        assertThat(r.getAmount()).isEqualByComparingTo(amount);
                        assertThat(r.getCurrency()).isEqualTo("USD");
                        assertThat(r.getEventTimestamp()).isEqualTo(eventTimestamp);
                        assertThat(r.isDuplicate()).isFalse();
                        assertThat(r.getMetadata()).containsEntry("reference", "REF-001");
                    });

            verify(eventRepository).save(argThat(record ->
                    record.getEventId().equals(eventId) &&
                    record.getAccountId().equals(accountId) &&
                    record.getType() == EventRecord.EventType.CREDIT &&
                    record.getAmount().compareTo(amount) == 0
            ));
            verify(accountServiceClient).applyTransaction(eq(accountId), argThat(requestBody ->
                    requestBody.get("eventId").equals(eventId) &&
                    requestBody.get("type").equals("CREDIT")
            ));
            verify(metricsService).incrementEventsProcessed("CREDIT");
            verify(metricsService, never()).incrementDuplicateEvents();
        }

        @Test
        @DisplayName("should successfully submit a DEBIT event")
        void submitEvent_SuccessfulDebitEvent() {
            // Arrange
            String eventId = "evt-789";
            String accountId = "acc-101";
            BigDecimal amount = new BigDecimal("50.50");
            Instant eventTimestamp = Instant.parse("2024-01-15T11:00:00Z");

            GatewayDtos.SubmitEventRequest request = new GatewayDtos.SubmitEventRequest();
            request.setEventId(eventId);
            request.setAccountId(accountId);
            request.setType("DEBIT");
            request.setAmount(amount);
            request.setCurrency("EUR");
            request.setEventTimestamp(eventTimestamp);

            when(eventRepository.existsByEventId(eventId)).thenReturn(false);
            when(eventRepository.save(any(EventRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountServiceClient.applyTransaction(eq(accountId), anyMap())).thenReturn(Map.of());

            // Act
            GatewayDtos.EventResponse response = eventService.submitEvent(request);

            // Assert
            assertThat(response)
                    .isNotNull()
                    .satisfies(r -> {
                        assertThat(r.getEventId()).isEqualTo(eventId);
                        assertThat(r.getType()).isEqualTo(EventRecord.EventType.DEBIT);
                    });

            verify(eventRepository).save(argThat(record -> record.getType() == EventRecord.EventType.DEBIT));
            verify(metricsService).incrementEventsProcessed("DEBIT");
        }

        @Test
        @DisplayName("should detect duplicate event and return existing record with duplicate flag")
        void submitEvent_DuplicateEventDetection() {
            // Arrange
            String eventId = "evt-dup-123";
            String accountId = "acc-999";
            BigDecimal amount = new BigDecimal("75.00");
            Instant eventTimestamp = Instant.parse("2024-01-15T12:00:00Z");
            Instant receivedAt = Instant.parse("2024-01-15T12:00:05Z");

            GatewayDtos.SubmitEventRequest request = new GatewayDtos.SubmitEventRequest();
            request.setEventId(eventId);
            request.setAccountId(accountId);
            request.setType("CREDIT");
            request.setAmount(amount);
            request.setCurrency("GBP");
            request.setEventTimestamp(eventTimestamp);

            EventRecord existingRecord = EventRecord.builder()
                    .eventId(eventId)
                    .accountId(accountId)
                    .type(EventRecord.EventType.CREDIT)
                    .amount(amount)
                    .currency("GBP")
                    .eventTimestamp(eventTimestamp)
                    .receivedAt(receivedAt)
                    .duplicate(false)
                    .metadataJson(null)
                    .build();

            when(eventRepository.existsByEventId(eventId)).thenReturn(true);
            when(eventRepository.findByEventId(eventId)).thenReturn(Optional.of(existingRecord));

            // Act
            GatewayDtos.EventResponse response = eventService.submitEvent(request);

            // Assert
            assertThat(response)
                    .isNotNull()
                    .satisfies(r -> {
                        assertThat(r.getEventId()).isEqualTo(eventId);
                        assertThat(r.isDuplicate()).isFalse();  // Original record not marked as duplicate
                    });

            verify(eventRepository, never()).save(any());
            verify(accountServiceClient, never()).applyTransaction(anyString(), anyMap());
            verify(metricsService).incrementDuplicateEvents();
            verify(metricsService, never()).incrementEventsProcessed(any());
        }

        @Test
        @DisplayName("should throw exception when event type is invalid")
        void submitEvent_InvalidEventType() {
            // Arrange
            GatewayDtos.SubmitEventRequest request = new GatewayDtos.SubmitEventRequest();
            request.setEventId("evt-invalid");
            request.setAccountId("acc-123");
            request.setType("INVALID_TYPE");
            request.setAmount(new BigDecimal("100.00"));
            request.setCurrency("USD");
            request.setEventTimestamp(Instant.now());

            // Act & Assert
            assertThatThrownBy(() -> eventService.submitEvent(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("type must be CREDIT or DEBIT")
                    .hasMessageContaining("INVALID_TYPE");

            verify(eventRepository, never()).save(any());
            verify(accountServiceClient, never()).applyTransaction(anyString(), anyMap());
            verify(metricsService, never()).incrementEventsProcessed(any());
        }

        @Test
        @DisplayName("should persist event before calling Account Service (eventual consistency)")
        void submitEvent_PersistBeforeAccountServiceCall() {
            // Arrange
            String eventId = "evt-persist-test";
            String accountId = "acc-persist";
            GatewayDtos.SubmitEventRequest request = new GatewayDtos.SubmitEventRequest();
            request.setEventId(eventId);
            request.setAccountId(accountId);
            request.setType("CREDIT");
            request.setAmount(new BigDecimal("100.00"));
            request.setCurrency("USD");
            request.setEventTimestamp(Instant.now());

            when(eventRepository.existsByEventId(eventId)).thenReturn(false);
            when(eventRepository.save(any(EventRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountServiceClient.applyTransaction(eq(accountId), anyMap())).thenReturn(Map.of());

            // Act
            eventService.submitEvent(request);

            // Assert
            ArgumentCaptor<EventRecord> captor = ArgumentCaptor.forClass(EventRecord.class);
            verify(eventRepository).save(captor.capture());
            verify(accountServiceClient).applyTransaction(eq(accountId), anyMap());

            // Verify save was called before applyTransaction by checking order
            InOrder inOrder = inOrder(eventRepository, accountServiceClient);
            inOrder.verify(eventRepository).save(any(EventRecord.class));
            inOrder.verify(accountServiceClient).applyTransaction(eq(accountId), anyMap());
        }

        @Test
        @DisplayName("should handle Account Service failure after local persistence")
        void submitEvent_AccountServiceFailureAfterPersistence() {
            // Arrange
            String eventId = "evt-service-fail";
            String accountId = "acc-fail";
            GatewayDtos.SubmitEventRequest request = new GatewayDtos.SubmitEventRequest();
            request.setEventId(eventId);
            request.setAccountId(accountId);
            request.setType("CREDIT");
            request.setAmount(new BigDecimal("100.00"));
            request.setCurrency("USD");
            request.setEventTimestamp(Instant.now());

            when(eventRepository.existsByEventId(eventId)).thenReturn(false);
            when(eventRepository.save(any(EventRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountServiceClient.applyTransaction(eq(accountId), anyMap()))
                    .thenThrow(new AccountServiceUnavailableException("Service unavailable"));

            // Act & Assert
            assertThatThrownBy(() -> eventService.submitEvent(request))
                    .isInstanceOf(AccountServiceUnavailableException.class)
                    .hasMessageContaining("Service unavailable");

            verify(eventRepository).save(any(EventRecord.class));
            verify(accountServiceClient).applyTransaction(eq(accountId), anyMap());
        }

        @Test
        @DisplayName("should handle Account Service client error")
        void submitEvent_AccountServiceClientError() {
            // Arrange
            String eventId = "evt-client-error";
            String accountId = "acc-error";
            GatewayDtos.SubmitEventRequest request = new GatewayDtos.SubmitEventRequest();
            request.setEventId(eventId);
            request.setAccountId(accountId);
            request.setType("CREDIT");
            request.setAmount(new BigDecimal("100.00"));
            request.setCurrency("USD");
            request.setEventTimestamp(Instant.now());

            when(eventRepository.existsByEventId(eventId)).thenReturn(false);
            when(eventRepository.save(any(EventRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountServiceClient.applyTransaction(eq(accountId), anyMap()))
                    .thenThrow(new AccountServiceException("Invalid account", new RuntimeException()));

            // Act & Assert
            assertThatThrownBy(() -> eventService.submitEvent(request))
                    .isInstanceOf(AccountServiceException.class);

            verify(eventRepository).save(any(EventRecord.class));
        }

        @Test
        @DisplayName("should correctly serialize metadata to JSON")
        void submitEvent_MetadataSerializationToJson() {
            // Arrange
            String eventId = "evt-metadata";
            String accountId = "acc-metadata";
            Map<String, Object> metadata = Map.of(
                    "reference", "REF-123",
                    "requestId", "req-456",
                    "source", "api"
            );

            GatewayDtos.SubmitEventRequest request = new GatewayDtos.SubmitEventRequest();
            request.setEventId(eventId);
            request.setAccountId(accountId);
            request.setType("CREDIT");
            request.setAmount(new BigDecimal("100.00"));
            request.setCurrency("USD");
            request.setEventTimestamp(Instant.now());
            request.setMetadata(metadata);

            when(eventRepository.existsByEventId(eventId)).thenReturn(false);
            when(eventRepository.save(any(EventRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountServiceClient.applyTransaction(eq(accountId), anyMap())).thenReturn(Map.of());

            // Act
            GatewayDtos.EventResponse response = eventService.submitEvent(request);

            // Assert
            ArgumentCaptor<EventRecord> captor = ArgumentCaptor.forClass(EventRecord.class);
            verify(eventRepository).save(captor.capture());
            EventRecord savedRecord = captor.getValue();

            assertThat(savedRecord.getMetadataJson()).isNotBlank();
            assertThat(response.getMetadata())
                    .containsAllEntriesOf(metadata);
        }

        @Test
        @DisplayName("should handle null metadata gracefully")
        void submitEvent_NullMetadata() {
            // Arrange
            String eventId = "evt-no-metadata";
            String accountId = "acc-no-metadata";

            GatewayDtos.SubmitEventRequest request = new GatewayDtos.SubmitEventRequest();
            request.setEventId(eventId);
            request.setAccountId(accountId);
            request.setType("CREDIT");
            request.setAmount(new BigDecimal("100.00"));
            request.setCurrency("USD");
            request.setEventTimestamp(Instant.now());
            request.setMetadata(null);

            when(eventRepository.existsByEventId(eventId)).thenReturn(false);
            when(eventRepository.save(any(EventRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountServiceClient.applyTransaction(eq(accountId), anyMap())).thenReturn(Map.of());

            // Act
            GatewayDtos.EventResponse response = eventService.submitEvent(request);

            // Assert
            assertThat(response.getMetadata()).isNull();
            verify(eventRepository).save(argThat(record -> record.getMetadataJson() == null));
        }

        @Test
        @DisplayName("should capture correct transaction request payload for Account Service")
        void submitEvent_CorrectAccountServicePayload() {
            // Arrange
            String eventId = "evt-payload";
            String accountId = "acc-payload";
            BigDecimal amount = new BigDecimal("250.75");
            Instant eventTimestamp = Instant.parse("2024-01-15T14:30:00Z");

            GatewayDtos.SubmitEventRequest request = new GatewayDtos.SubmitEventRequest();
            request.setEventId(eventId);
            request.setAccountId(accountId);
            request.setType("DEBIT");
            request.setAmount(amount);
            request.setCurrency("CAD");
            request.setEventTimestamp(eventTimestamp);

            when(eventRepository.existsByEventId(eventId)).thenReturn(false);
            when(eventRepository.save(any(EventRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountServiceClient.applyTransaction(eq(accountId), anyMap())).thenReturn(Map.of());

            // Act
            eventService.submitEvent(request);

            // Assert
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(accountServiceClient).applyTransaction(eq(accountId), payloadCaptor.capture());

            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload)
                    .containsEntry("eventId", eventId)
                    .containsEntry("accountId", accountId)
                    .containsEntry("type", "DEBIT")
                    .containsEntry("currency", "CAD");
            assertThat(payload.get("amount")).isEqualTo(amount);
        }

        @Test
        @DisplayName("should set receivedAt timestamp to current time")
        void submitEvent_ReceivingTimestampSetToCurrent() {
            // Arrange
            String eventId = "evt-timestamp";
            String accountId = "acc-timestamp";
            Instant eventTimestamp = Instant.parse("2024-01-15T10:00:00Z");

            GatewayDtos.SubmitEventRequest request = new GatewayDtos.SubmitEventRequest();
            request.setEventId(eventId);
            request.setAccountId(accountId);
            request.setType("CREDIT");
            request.setAmount(new BigDecimal("100.00"));
            request.setCurrency("USD");
            request.setEventTimestamp(eventTimestamp);

            when(eventRepository.existsByEventId(eventId)).thenReturn(false);
            when(eventRepository.save(any(EventRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountServiceClient.applyTransaction(eq(accountId), anyMap())).thenReturn(Map.of());

            Instant beforeCall = Instant.now();

            // Act
            eventService.submitEvent(request);

            Instant afterCall = Instant.now();

            // Assert
            ArgumentCaptor<EventRecord> captor = ArgumentCaptor.forClass(EventRecord.class);
            verify(eventRepository).save(captor.capture());

            Instant savedReceivedAt = captor.getValue().getReceivedAt();
            assertThat(savedReceivedAt)
                    .isNotNull()
                    .isAfterOrEqualTo(beforeCall)
                    .isBeforeOrEqualTo(afterCall);
        }
    }

    @Nested
    @DisplayName("getEvent() tests")
    class GetEventTests {

        @Test
        @DisplayName("should successfully retrieve an existing event")
        void getEvent_SuccessfulRetrieval() {
            // Arrange
            String eventId = "evt-retrieve-123";
            String accountId = "acc-retrieve";
            BigDecimal amount = new BigDecimal("500.00");
            Instant eventTimestamp = Instant.parse("2024-01-15T15:30:00Z");
            Instant receivedAt = Instant.parse("2024-01-15T15:30:05Z");

            EventRecord record = EventRecord.builder()
                    .eventId(eventId)
                    .accountId(accountId)
                    .type(EventRecord.EventType.CREDIT)
                    .amount(amount)
                    .currency("USD")
                    .eventTimestamp(eventTimestamp)
                    .receivedAt(receivedAt)
                    .duplicate(false)
                    .metadataJson(null)
                    .build();

            when(eventRepository.findByEventId(eventId)).thenReturn(Optional.of(record));

            // Act
            GatewayDtos.EventResponse response = eventService.getEvent(eventId);

            // Assert
            assertThat(response)
                    .isNotNull()
                    .satisfies(r -> {
                        assertThat(r.getEventId()).isEqualTo(eventId);
                        assertThat(r.getAccountId()).isEqualTo(accountId);
                        assertThat(r.getType()).isEqualTo(EventRecord.EventType.CREDIT);
                        assertThat(r.getAmount()).isEqualByComparingTo(amount);
                        assertThat(r.getCurrency()).isEqualTo("USD");
                        assertThat(r.getEventTimestamp()).isEqualTo(eventTimestamp);
                        assertThat(r.getReceivedAt()).isEqualTo(receivedAt);
                        assertThat(r.isDuplicate()).isFalse();
                    });

            verify(eventRepository).findByEventId(eventId);
            verify(accountServiceClient, never()).applyTransaction(anyString(), anyMap());
        }

        @Test
        @DisplayName("should throw NoSuchElementException when event not found")
        void getEvent_EventNotFound() {
            // Arrange
            String eventId = "evt-nonexistent";

            when(eventRepository.findByEventId(eventId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> eventService.getEvent(eventId))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("Event not found")
                    .hasMessageContaining(eventId);

            verify(eventRepository).findByEventId(eventId);
        }

        @Test
        @DisplayName("should work independently of Account Service availability")
        void getEvent_WorksWithoutAccountService() {
            // Arrange
            String eventId = "evt-independent";
            EventRecord record = EventRecord.builder()
                    .eventId(eventId)
                    .accountId("acc-123")
                    .type(EventRecord.EventType.DEBIT)
                    .amount(new BigDecimal("100.00"))
                    .currency("EUR")
                    .eventTimestamp(Instant.now())
                    .receivedAt(Instant.now())
                    .duplicate(false)
                    .metadataJson(null)
                    .build();

            when(eventRepository.findByEventId(eventId)).thenReturn(Optional.of(record));

            // Act
            GatewayDtos.EventResponse response = eventService.getEvent(eventId);

            // Assert
            assertThat(response).isNotNull();
            verify(accountServiceClient, never()).applyTransaction(anyString(), anyMap());
            verify(accountServiceClient, never()).getBalance(anyString());
        }

        @Test
        @DisplayName("should deserialize metadata correctly from stored JSON")
        void getEvent_MetadataDeserialization() {
            // Arrange
            String eventId = "evt-metadata-deser";
            String metadataJson = "{\"reference\":\"REF-789\",\"requestId\":\"req-999\"}";

            EventRecord record = EventRecord.builder()
                    .eventId(eventId)
                    .accountId("acc-metadata")
                    .type(EventRecord.EventType.CREDIT)
                    .amount(new BigDecimal("100.00"))
                    .currency("USD")
                    .eventTimestamp(Instant.now())
                    .receivedAt(Instant.now())
                    .duplicate(false)
                    .metadataJson(metadataJson)
                    .build();

            when(eventRepository.findByEventId(eventId)).thenReturn(Optional.of(record));

            // Act
            GatewayDtos.EventResponse response = eventService.getEvent(eventId);

            // Assert
            assertThat(response.getMetadata())
                    .containsEntry("reference", "REF-789")
                    .containsEntry("requestId", "req-999");
        }

        @Test
        @DisplayName("should handle null metadata JSON gracefully")
        void getEvent_NullMetadataJson() {
            // Arrange
            String eventId = "evt-no-meta";
            EventRecord record = EventRecord.builder()
                    .eventId(eventId)
                    .accountId("acc-123")
                    .type(EventRecord.EventType.CREDIT)
                    .amount(new BigDecimal("100.00"))
                    .currency("USD")
                    .eventTimestamp(Instant.now())
                    .receivedAt(Instant.now())
                    .duplicate(false)
                    .metadataJson(null)
                    .build();

            when(eventRepository.findByEventId(eventId)).thenReturn(Optional.of(record));

            // Act
            GatewayDtos.EventResponse response = eventService.getEvent(eventId);

            // Assert
            assertThat(response.getMetadata()).isNull();
        }
    }

    @Nested
    @DisplayName("listEventsByAccount() tests")
    class ListEventsByAccountTests {

        @Test
        @DisplayName("should retrieve all events for an account in chronological order")
        void listEventsByAccount_SuccessfulRetrieval() {
            // Arrange
            String accountId = "acc-list-123";
            Instant timestamp1 = Instant.parse("2024-01-15T10:00:00Z");
            Instant timestamp2 = Instant.parse("2024-01-15T11:30:00Z");
            Instant timestamp3 = Instant.parse("2024-01-15T14:00:00Z");

            List<EventRecord> records = List.of(
                    EventRecord.builder()
                            .eventId("evt-001")
                            .accountId(accountId)
                            .type(EventRecord.EventType.CREDIT)
                            .amount(new BigDecimal("100.00"))
                            .currency("USD")
                            .eventTimestamp(timestamp1)
                            .receivedAt(Instant.now())
                            .duplicate(false)
                            .metadataJson(null)
                            .build(),
                    EventRecord.builder()
                            .eventId("evt-002")
                            .accountId(accountId)
                            .type(EventRecord.EventType.DEBIT)
                            .amount(new BigDecimal("50.00"))
                            .currency("USD")
                            .eventTimestamp(timestamp2)
                            .receivedAt(Instant.now())
                            .duplicate(false)
                            .metadataJson(null)
                            .build(),
                    EventRecord.builder()
                            .eventId("evt-003")
                            .accountId(accountId)
                            .type(EventRecord.EventType.CREDIT)
                            .amount(new BigDecimal("75.50"))
                            .currency("USD")
                            .eventTimestamp(timestamp3)
                            .receivedAt(Instant.now())
                            .duplicate(false)
                            .metadataJson(null)
                            .build()
            );

            when(eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId)).thenReturn(records);

            // Act
            List<GatewayDtos.EventResponse> responses = eventService.listEventsByAccount(accountId);

            // Assert
            assertThat(responses)
                    .hasSize(3)
                    .allSatisfy(r -> assertThat(r.getAccountId()).isEqualTo(accountId));

            assertThat(responses.get(0).getEventId()).isEqualTo("evt-001");
            assertThat(responses.get(1).getEventId()).isEqualTo("evt-002");
            assertThat(responses.get(2).getEventId()).isEqualTo("evt-003");

            // Verify chronological ordering by eventTimestamp
            assertThat(responses)
                    .map(GatewayDtos.EventResponse::getEventTimestamp)
                    .isSorted();

            verify(eventRepository).findByAccountIdOrderByEventTimestampAsc(accountId);
        }

        @Test
        @DisplayName("should return empty list when account has no events")
        void listEventsByAccount_EmptyList() {
            // Arrange
            String accountId = "acc-empty";

            when(eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId)).thenReturn(List.of());

            // Act
            List<GatewayDtos.EventResponse> responses = eventService.listEventsByAccount(accountId);

            // Assert
            assertThat(responses).isEmpty();
            verify(eventRepository).findByAccountIdOrderByEventTimestampAsc(accountId);
        }

        @Test
        @DisplayName("should preserve chronological order from repository")
        void listEventsByAccount_PreservesChronologicalOrder() {
            // Arrange
            String accountId = "acc-order";
            List<EventRecord> orderedRecords = List.of(
                    createEventRecord("evt-1", accountId, Instant.parse("2024-01-01T08:00:00Z")),
                    createEventRecord("evt-2", accountId, Instant.parse("2024-01-01T09:15:00Z")),
                    createEventRecord("evt-3", accountId, Instant.parse("2024-01-01T10:30:00Z")),
                    createEventRecord("evt-4", accountId, Instant.parse("2024-01-01T12:45:00Z"))
            );

            when(eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId)).thenReturn(orderedRecords);

            // Act
            List<GatewayDtos.EventResponse> responses = eventService.listEventsByAccount(accountId);

            // Assert
            assertThat(responses)
                    .hasSize(4)
                    .extracting(GatewayDtos.EventResponse::getEventTimestamp)
                    .containsExactly(
                            Instant.parse("2024-01-01T08:00:00Z"),
                            Instant.parse("2024-01-01T09:15:00Z"),
                            Instant.parse("2024-01-01T10:30:00Z"),
                            Instant.parse("2024-01-01T12:45:00Z")
                    );
        }

        @Test
        @DisplayName("should correctly map all event record fields to response")
        void listEventsByAccount_CorrectFieldMapping() {
            // Arrange
            String accountId = "acc-mapping";
            BigDecimal amount = new BigDecimal("123.45");
            Instant eventTimestamp = Instant.parse("2024-01-15T16:00:00Z");
            Instant receivedAt = Instant.parse("2024-01-15T16:00:10Z");

            EventRecord record = EventRecord.builder()
                    .eventId("evt-map")
                    .accountId(accountId)
                    .type(EventRecord.EventType.DEBIT)
                    .amount(amount)
                    .currency("GBP")
                    .eventTimestamp(eventTimestamp)
                    .receivedAt(receivedAt)
                    .duplicate(true)
                    .metadataJson(null)
                    .build();

            when(eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId)).thenReturn(List.of(record));

            // Act
            List<GatewayDtos.EventResponse> responses = eventService.listEventsByAccount(accountId);

            // Assert
            assertThat(responses)
                    .singleElement()
                    .satisfies(r -> {
                        assertThat(r.getEventId()).isEqualTo("evt-map");
                        assertThat(r.getAccountId()).isEqualTo(accountId);
                        assertThat(r.getType()).isEqualTo(EventRecord.EventType.DEBIT);
                        assertThat(r.getAmount()).isEqualByComparingTo(amount);
                        assertThat(r.getCurrency()).isEqualTo("GBP");
                        assertThat(r.getEventTimestamp()).isEqualTo(eventTimestamp);
                        assertThat(r.getReceivedAt()).isEqualTo(receivedAt);
                        assertThat(r.isDuplicate()).isTrue();
                    });
        }

        @Test
        @DisplayName("should work independently of Account Service")
        void listEventsByAccount_WorksWithoutAccountService() {
            // Arrange
            String accountId = "acc-independent";
            EventRecord record = createEventRecord("evt-ind", accountId, Instant.now());

            when(eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId)).thenReturn(List.of(record));

            // Act
            List<GatewayDtos.EventResponse> responses = eventService.listEventsByAccount(accountId);

            // Assert
            assertThat(responses).hasSize(1);
            verify(accountServiceClient, never()).applyTransaction(anyString(), anyMap());
            verify(accountServiceClient, never()).getBalance(anyString());
        }
    }

    // ============================================================================
    // Helper methods
    // ============================================================================

    private EventRecord createEventRecord(String eventId, String accountId, Instant eventTimestamp) {
        return EventRecord.builder()
                .eventId(eventId)
                .accountId(accountId)
                .type(EventRecord.EventType.CREDIT)
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .eventTimestamp(eventTimestamp)
                .receivedAt(Instant.now())
                .duplicate(false)
                .metadataJson(null)
                .build();
    }
}
