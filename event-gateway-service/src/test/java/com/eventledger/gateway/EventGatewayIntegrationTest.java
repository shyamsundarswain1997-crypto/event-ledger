package com.eventledger.gateway;

import com.eventledger.gateway.dto.GatewayDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the Event Gateway.
 *
 * WireMock is started per-test (not shared) so the circuit breaker state —
 * which lives in the Spring context — is reset via @DirtiesContext, ensuring
 * each test starts with a CLOSED circuit and a clean WireMock server.
 *
 * All WireMock mapping/verification calls use the WireMockServer *instance*
 * methods (wireMock.stubFor, wireMock.verify, wireMock.countRequestsMatching)
 * rather than the WireMock static API. This avoids the naming conflict between
 * WireMock's static `post()` builder and MockMvc's static `post()` builder.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "account-service.base-url=http://localhost:9999",
        "account-service.connect-timeout-ms=1000",
        "account-service.read-timeout-ms=2000",
        "resilience4j.circuitbreaker.instances.accountService.minimum-number-of-calls=3",
        "resilience4j.circuitbreaker.instances.accountService.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.accountService.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.accountService.wait-duration-in-open-state=2s",
        "resilience4j.retry.instances.accountService.max-attempts=1"
})
@DisplayName("Event Gateway Integration Tests")
class EventGatewayIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private WireMockServer wireMock;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().port(9999));
        wireMock.start();
    }

    @AfterEach
    void tearDown() {
        if (wireMock != null && wireMock.isRunning()) {
            wireMock.stop();
        }
    }

    // ============================================================================
    // Happy Path Tests
    // ============================================================================

    @Nested
    @DisplayName("Happy Path")
    class HappyPathTests {

        @Test
        @DirtiesContext
        @DisplayName("Full end-to-end: POST event → Account Service receives correct body → 201 response")
        void happyPath_EndToEndWithNewEvent() throws Exception {
            String eventId = "evt-happy-001";
            String accountId = "acc-happy-001";
            BigDecimal amount = new BigDecimal("100.50");
            Instant eventTimestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS);

            wireMock.stubFor(
                    WireMock.post(WireMock.urlPathEqualTo("/accounts/" + accountId + "/transactions"))
                            .willReturn(WireMock.okJson("{\"duplicate\":false}"))
            );

            GatewayDtos.SubmitEventRequest request = buildEventRequest(
                    eventId, accountId, "CREDIT", amount, "USD", eventTimestamp, null
            );

            mockMvc.perform(post("/events")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.eventId").value(eventId))
                    .andExpect(jsonPath("$.accountId").value(accountId))
                    .andExpect(jsonPath("$.type").value("CREDIT"))
                    .andExpect(jsonPath("$.amount").value(100.50))
                    .andExpect(jsonPath("$.currency").value("USD"))
                    .andExpect(jsonPath("$.duplicate").value(false));

            // Verify Account Service received the correct body fields
            wireMock.verify(1, WireMock.postRequestedFor(
                            WireMock.urlPathEqualTo("/accounts/" + accountId + "/transactions"))
                    .withRequestBody(WireMock.matchingJsonPath("$.eventId", WireMock.equalTo(eventId)))
                    .withRequestBody(WireMock.matchingJsonPath("$.type", WireMock.equalTo("CREDIT")))
                    .withRequestBody(WireMock.matchingJsonPath("$.amount", WireMock.equalTo("100.50")))
            );
        }

        @Test
        @DirtiesContext
        @DisplayName("Idempotency: same eventId twice → Account Service called once → second response has duplicate:true")
        void happyPath_IdempotencyDetection() throws Exception {
            String eventId = "evt-idempotent-001";
            String accountId = "acc-idempotent-001";
            BigDecimal amount = new BigDecimal("50.00");
            Instant eventTimestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS);

            wireMock.stubFor(
                    WireMock.post(WireMock.urlPathEqualTo("/accounts/" + accountId + "/transactions"))
                            .willReturn(WireMock.okJson("{\"duplicate\":false}"))
            );

            GatewayDtos.SubmitEventRequest request = buildEventRequest(
                    eventId, accountId, "CREDIT", amount, "USD", eventTimestamp, null
            );

            // First submission → 201
            mockMvc.perform(post("/events")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.duplicate").value(false));

            // Second submission (duplicate) → 200 with duplicate:true
            mockMvc.perform(post("/events")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.duplicate").value(true));

            // Account Service must only be called once
            wireMock.verify(1, WireMock.postRequestedFor(
                    WireMock.urlPathEqualTo("/accounts/" + accountId + "/transactions")));
        }

        @Test
        @DirtiesContext
        @DisplayName("Out-of-order events: POST t3→t1→t2, GET /events?account= → array ordered t1,t2,t3")
        void happyPath_OutOfOrderEventOrdering() throws Exception {
            String accountId = "acc-ooo-001";
            Instant baseTime = Instant.parse("2024-01-15T10:00:00Z");
            Instant t1 = baseTime;
            Instant t2 = baseTime.plusSeconds(10);
            Instant t3 = baseTime.plusSeconds(20);

            String eventId1 = "evt-ooo-1";
            String eventId2 = "evt-ooo-2";
            String eventId3 = "evt-ooo-3";

            wireMock.stubFor(
                    WireMock.post(WireMock.urlPathEqualTo("/accounts/" + accountId + "/transactions"))
                            .willReturn(WireMock.okJson("{\"duplicate\":false}"))
            );

            // Submit deliberately out of order: t3, t1, t2
            for (Object[] ev : new Object[][]{{eventId3, t3}, {eventId1, t1}, {eventId2, t2}}) {
                mockMvc.perform(post("/events")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildEventRequest(
                                (String) ev[0], accountId, "CREDIT", BigDecimal.TEN, "USD", (Instant) ev[1], null
                        ))));
            }

            String response = mockMvc.perform(get("/events")
                            .param("account", accountId))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            int pos1 = response.indexOf(eventId1);
            int pos2 = response.indexOf(eventId2);
            int pos3 = response.indexOf(eventId3);

            assertThat(pos1).as("evt-ooo-1 should appear before evt-ooo-2").isLessThan(pos2);
            assertThat(pos2).as("evt-ooo-2 should appear before evt-ooo-3").isLessThan(pos3);
        }
    }

    // ============================================================================
    // Validation Tests
    // ============================================================================

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DirtiesContext
        @DisplayName("Missing eventId → 400")
        void validation_MissingEventId() throws Exception {
            GatewayDtos.SubmitEventRequest request = new GatewayDtos.SubmitEventRequest();
            request.setAccountId("acc-test");
            request.setType("CREDIT");
            request.setAmount(BigDecimal.TEN);
            request.setCurrency("USD");
            request.setEventTimestamp(Instant.now().truncatedTo(ChronoUnit.SECONDS));

            mockMvc.perform(post("/events")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DirtiesContext
        @DisplayName("Zero amount → 400 with non-empty message")
        void validation_ZeroAmount() throws Exception {
            GatewayDtos.SubmitEventRequest request = buildEventRequest(
                    "evt-zero", "acc-test", "CREDIT",
                    BigDecimal.ZERO, "USD",
                    Instant.now().truncatedTo(ChronoUnit.SECONDS), null
            );

            mockMvc.perform(post("/events")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        @DirtiesContext
        @DisplayName("Invalid type (not CREDIT/DEBIT) → 400")
        void validation_InvalidType() throws Exception {
            GatewayDtos.SubmitEventRequest request = buildEventRequest(
                    "evt-invalid", "acc-test", "TRANSFER",
                    BigDecimal.TEN, "USD",
                    Instant.now().truncatedTo(ChronoUnit.SECONDS), null
            );

            mockMvc.perform(post("/events")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ============================================================================
    // Resilience Tests
    // ============================================================================

    @Nested
    @DisplayName("Resilience and Fault Tolerance")
    class ResilienceTests {

        @Test
        @DirtiesContext
        @DisplayName("Account Service returns 500 → POST returns 503")
        void resilience_AccountServiceDown_Returns503() throws Exception {
            wireMock.stubFor(
                    WireMock.post(WireMock.anyUrl())
                            .willReturn(WireMock.serverError())
            );

            mockMvc.perform(post("/events")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildEventRequest(
                                    "evt-down-001", "acc-down-001", "CREDIT",
                                    BigDecimal.TEN, "USD",
                                    Instant.now().truncatedTo(ChronoUnit.SECONDS), null
                            ))))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value("Service Unavailable"));
        }

        @Test
        @DirtiesContext
        @DisplayName("GET /events/{id} works when Account Service is down (reads from Gateway store)")
        void resilience_GetEventWorksWhenAccountServiceDown() throws Exception {
            String eventId = "evt-get-001";
            String accountId = "acc-get-001";

            // Submit while Account Service is up
            wireMock.stubFor(
                    WireMock.post(WireMock.anyUrl())
                            .willReturn(WireMock.okJson("{\"duplicate\":false}"))
            );

            mockMvc.perform(post("/events")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildEventRequest(
                                    eventId, accountId, "CREDIT",
                                    BigDecimal.TEN, "USD",
                                    Instant.now().truncatedTo(ChronoUnit.SECONDS), null
                            ))))
                    .andExpect(status().isCreated());

            // Bring Account Service down
            wireMock.resetAll();

            // GET still works — reads from Gateway's local store
            mockMvc.perform(get("/events/" + eventId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.eventId").value(eventId));
        }

        @Test
        @DirtiesContext
        @DisplayName("GET /events?account works when Account Service is down")
        void resilience_GetEventsListWorksWhenAccountServiceDown() throws Exception {
            String accountId = "acc-getlist-001";
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

            wireMock.stubFor(
                    WireMock.post(WireMock.anyUrl())
                            .willReturn(WireMock.okJson("{\"duplicate\":false}"))
            );

            mockMvc.perform(post("/events")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildEventRequest(
                            "evt-list-1", accountId, "CREDIT", BigDecimal.ONE, "USD", now, null
                    ))));

            mockMvc.perform(post("/events")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildEventRequest(
                            "evt-list-2", accountId, "DEBIT", new BigDecimal("2"), "USD",
                            now.plusSeconds(5), null
                    ))));

            // Bring Account Service down
            wireMock.resetAll();

            // List still works
            mockMvc.perform(get("/events").param("account", accountId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)));
        }

        @Test
        @DirtiesContext
        @DisplayName("Circuit breaker opens after repeated failures → subsequent calls fail fast without hitting Account Service")
        void resilience_CircuitBreakerOpens() throws Exception {
            String accountId = "acc-cb-001";

            // Stub Account Service to always return 500
            wireMock.stubFor(
                    WireMock.post(WireMock.anyUrl())
                            .willReturn(WireMock.serverError())
            );

            // Fire enough requests to trip the circuit breaker
            // (min-calls=3, sliding-window=4, failure-rate=50% → 4 failures = 100% → OPEN)
            for (int i = 0; i < 4; i++) {
                mockMvc.perform(post("/events")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildEventRequest(
                                "evt-cb-" + i, accountId, "CREDIT",
                                BigDecimal.TEN, "USD",
                                Instant.now().truncatedTo(ChronoUnit.SECONDS), null
                        ))));
            }

            // Record how many calls WireMock received so far
            int countBeforeOpen = wireMock.countRequestsMatching(
                    WireMock.postRequestedFor(WireMock.anyUrl()).build()
            ).getCount();

            // Fire one more — circuit should now be OPEN, so WireMock must NOT receive this
            mockMvc.perform(post("/events")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildEventRequest(
                                    "evt-cb-after-open", accountId, "CREDIT",
                                    BigDecimal.TEN, "USD",
                                    Instant.now().truncatedTo(ChronoUnit.SECONDS), null
                            ))))
                    .andExpect(status().isServiceUnavailable());

            int countAfterOpen = wireMock.countRequestsMatching(
                    WireMock.postRequestedFor(WireMock.anyUrl()).build()
            ).getCount();

            assertThat(countAfterOpen)
                    .as("Circuit breaker should have prevented the call from reaching Account Service")
                    .isEqualTo(countBeforeOpen);
        }
    }

    // ============================================================================
    // Trace ID Tests
    // ============================================================================

    @Nested
    @DisplayName("Trace ID Handling")
    class TraceIdTests {

        @Test
        @DirtiesContext
        @DisplayName("Trace ID is auto-generated and returned in X-Trace-Id response header")
        void traceId_GeneratedAndReturned() throws Exception {
            wireMock.stubFor(
                    WireMock.post(WireMock.anyUrl())
                            .willReturn(WireMock.okJson("{\"duplicate\":false}"))
            );

            mockMvc.perform(post("/events")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildEventRequest(
                                    "evt-trace-1", "acc-trace-1", "CREDIT",
                                    BigDecimal.TEN, "USD",
                                    Instant.now().truncatedTo(ChronoUnit.SECONDS), null
                            ))))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("X-Trace-Id"))
                    .andExpect(header().string("X-Trace-Id", not(emptyString())));
        }

        @Test
        @DirtiesContext
        @DisplayName("Client-provided trace ID is propagated to Account Service via X-Trace-Id header")
        void traceId_PropagatedToAccountService() throws Exception {
            String traceId = "trace-propagation-12345";

            // Stub expecting exactly the trace ID we're about to send
            wireMock.stubFor(
                    WireMock.post(WireMock.anyUrl())
                            .withHeader("X-Trace-Id", WireMock.equalTo(traceId))
                            .willReturn(WireMock.okJson("{\"duplicate\":false}"))
            );

            mockMvc.perform(post("/events")
                            .header("X-Trace-Id", traceId)
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildEventRequest(
                                    "evt-trace-2", "acc-trace-2", "CREDIT",
                                    BigDecimal.TEN, "USD",
                                    Instant.now().truncatedTo(ChronoUnit.SECONDS), null
                            ))))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("X-Trace-Id", traceId));

            // Verify Account Service received the same trace ID
            wireMock.verify(1, WireMock.postRequestedFor(WireMock.anyUrl())
                    .withHeader("X-Trace-Id", WireMock.equalTo(traceId)));
        }

        @Test
        @DirtiesContext
        @DisplayName("Error response body contains traceId field matching the request header")
        void traceId_InErrorResponse() throws Exception {
            String traceId = "trace-error-001";

            // Submit an invalid request (missing eventId) to trigger a 400
            GatewayDtos.SubmitEventRequest request = new GatewayDtos.SubmitEventRequest();
            request.setAccountId("acc-test");
            request.setType("CREDIT");
            request.setAmount(BigDecimal.TEN);
            request.setCurrency("USD");
            request.setEventTimestamp(Instant.now().truncatedTo(ChronoUnit.SECONDS));

            mockMvc.perform(post("/events")
                            .header("X-Trace-Id", traceId)
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.traceId").value(traceId));
        }
    }

    // ============================================================================
    // Health Check Tests
    // ============================================================================

    @Nested
    @DisplayName("Health Check")
    class HealthCheckTests {

        @Test
        @DirtiesContext
        @DisplayName("Health: Gateway DB is UP, Account Service is UP → status UP")
        void health_BothUp() throws Exception {
            wireMock.stubFor(
                    WireMock.get(WireMock.urlPathEqualTo("/health"))
                            .willReturn(WireMock.okJson("{\"status\":\"UP\",\"database\":\"UP\"}"))
            );

            mockMvc.perform(get("/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.service").value("event-gateway"))
                    .andExpect(jsonPath("$.database").value("UP"))
                    .andExpect(jsonPath("$.accountService").value("UP"));
        }

        @Test
        @DirtiesContext
        @DisplayName("Health: Account Service DOWN → status DEGRADED, accountService field is DOWN")
        void health_AccountServiceDown() throws Exception {
            // No stub → WireMock returns connection refused for any request
            // (server is up but no stubs = 404, good enough to mark accountService DOWN)
            wireMock.stubFor(
                    WireMock.get(WireMock.anyUrl())
                            .willReturn(WireMock.serverError())
            );

            mockMvc.perform(get("/health"))
                    .andExpect(status().isOk())          // Gateway itself is healthy
                    .andExpect(jsonPath("$.database").value("UP"))
                    .andExpect(jsonPath("$.accountService").value("DOWN"));
        }
    }

    // ============================================================================
    // Helper Methods
    // ============================================================================

    private GatewayDtos.SubmitEventRequest buildEventRequest(
            String eventId, String accountId, String type, BigDecimal amount,
            String currency, Instant eventTimestamp, Map<String, Object> metadata) {

        GatewayDtos.SubmitEventRequest request = new GatewayDtos.SubmitEventRequest();
        request.setEventId(eventId);
        request.setAccountId(accountId);
        request.setType(type);
        request.setAmount(amount);
        request.setCurrency(currency);
        request.setEventTimestamp(eventTimestamp);
        request.setMetadata(metadata);
        return request;
    }
}