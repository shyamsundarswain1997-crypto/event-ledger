package com.eventledger.account;

import com.eventledger.account.dto.AccountDtos;
import com.eventledger.account.model.Transaction;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@DisplayName("Account Service Integration Tests")
class AccountServiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ============================================================================
    // Balance Calculation Tests
    // ============================================================================

    @Nested
    @DisplayName("Balance Calculation")
    class BalanceCalculationTests {

        @Test
        @DirtiesContext
        @DisplayName("CREDIT increases balance")
        void balance_CreditIncreasesBalance() throws Exception {
            String accountId = "acc-credit-001";
            String eventId = "evt-credit-001";
            BigDecimal creditAmount = new BigDecimal("150.00");
            Instant eventTimestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS);

            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    eventId, accountId, Transaction.TransactionType.CREDIT, creditAmount, "USD", eventTimestamp
            );

            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.duplicate").value(false));

            // Verify balance
            mockMvc.perform(get("/accounts/" + accountId + "/balance")
                    .contentType(APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(150.00))
                    .andExpect(jsonPath("$.currency").value("USD"));
        }

        @Test
        @DirtiesContext
        @DisplayName("DEBIT decreases balance")
        void balance_DebitDecreasesBalance() throws Exception {
            String accountId = "acc-debit-001";
            Instant baseTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);

            // First, add credit
            String creditEventId = "evt-credit-debit";
            BigDecimal creditAmount = new BigDecimal("200.00");

            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildTransactionRequest(
                            creditEventId, accountId, Transaction.TransactionType.CREDIT, creditAmount, "USD", baseTime
                    ))));

            // Then, apply debit
            String debitEventId = "evt-debit-debit";
            BigDecimal debitAmount = new BigDecimal("50.00");

            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildTransactionRequest(
                            debitEventId, accountId, Transaction.TransactionType.DEBIT, debitAmount, "USD", baseTime.plusSeconds(1)
                    ))));

            // Verify balance = 200 - 50 = 150
            mockMvc.perform(get("/accounts/" + accountId + "/balance")
                    .contentType(APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(150.00));
        }

        @Test
        @DirtiesContext
        @DisplayName("Multiple credits and debits → correct sum")
        void balance_MultipleTransactions() throws Exception {
            String accountId = "acc-multi-001";
            Instant baseTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);

            // Add credit +100
            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildTransactionRequest(
                            "evt-1", accountId, Transaction.TransactionType.CREDIT, new BigDecimal("100.00"), "USD", baseTime
                    ))));

            // Add credit +50
            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildTransactionRequest(
                            "evt-2", accountId, Transaction.TransactionType.CREDIT, new BigDecimal("50.00"), "USD", baseTime.plusSeconds(1)
                    ))));

            // Add debit -30
            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildTransactionRequest(
                            "evt-3", accountId, Transaction.TransactionType.DEBIT, new BigDecimal("30.00"), "USD", baseTime.plusSeconds(2)
                    ))));

            // Add debit -20
            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildTransactionRequest(
                            "evt-4", accountId, Transaction.TransactionType.DEBIT, new BigDecimal("20.00"), "USD", baseTime.plusSeconds(3)
                    ))));

            // Verify balance = 100 + 50 - 30 - 20 = 100
            mockMvc.perform(get("/accounts/" + accountId + "/balance")
                    .contentType(APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(100.00));
        }
    }

    // ============================================================================
    // Idempotency Tests
    // ============================================================================

    @Nested
    @DisplayName("Idempotency")
    class IdempotencyTests {

        @Test
        @DirtiesContext
        @DisplayName("Duplicate eventId → duplicate:true, balance unchanged")
        void idempotency_DuplicateEventId() throws Exception {
            String accountId = "acc-idem-001";
            String eventId = "evt-idem-001";
            BigDecimal amount = new BigDecimal("75.00");
            Instant eventTimestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS);

            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    eventId, accountId, Transaction.TransactionType.CREDIT, amount, "USD", eventTimestamp
            );

            // First submission
            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.duplicate").value(false))
                    .andExpect(jsonPath("$.eventId").value(eventId));

            // Get balance after first submission
            String balanceAfterFirst = mockMvc.perform(get("/accounts/" + accountId + "/balance")
                    .contentType(APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            // Second submission (duplicate)
            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.duplicate").value(true))
                    .andExpect(jsonPath("$.eventId").value(eventId));

            // Get balance after second submission - should be identical
            String balanceAfterSecond = mockMvc.perform(get("/accounts/" + accountId + "/balance")
                    .contentType(APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(balanceAfterFirst).isEqualTo(balanceAfterSecond);
        }

        @Test
        @DirtiesContext
        @DisplayName("Multiple duplicate submissions → consistent response")
        void idempotency_MultipleDuplicateSubmissions() throws Exception {
            String accountId = "acc-idem-multi";
            String eventId = "evt-idem-multi";
            BigDecimal amount = new BigDecimal("100.00");
            Instant eventTimestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS);

            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    eventId, accountId, Transaction.TransactionType.CREDIT, amount, "USD", eventTimestamp
            );

            // First submission
            String firstResponse = mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            // Multiple resubmissions
            for (int i = 0; i < 3; i++) {
                String duplicateResponse = mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.duplicate").value(true))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

                // Responses should be consistent
                assertThat(duplicateResponse).contains("\"duplicate\":true");
            }
        }
    }

    // ============================================================================
    // Out-of-Order Event Handling
    // ============================================================================

    @Nested
    @DisplayName("Out-of-Order Correctness")
    class OutOfOrderTests {

        @Test
        @DirtiesContext
        @DisplayName("Submit events t3→t1→t2 → balance correct regardless of order")
        void outOfOrder_CorrectBalance() throws Exception {
            String accountId = "acc-ooo-001";
            Instant baseTime = Instant.parse("2024-01-15T10:00:00Z");
            Instant t1 = baseTime;
            Instant t2 = baseTime.plusSeconds(10);
            Instant t3 = baseTime.plusSeconds(20);

            // Submit in order: t3, t1, t2
            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildTransactionRequest(
                            "evt-t3", accountId, Transaction.TransactionType.CREDIT, new BigDecimal("30.00"), "USD", t3
                    ))));

            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildTransactionRequest(
                            "evt-t1", accountId, Transaction.TransactionType.CREDIT, new BigDecimal("10.00"), "USD", t1
                    ))));

            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildTransactionRequest(
                            "evt-t2", accountId, Transaction.TransactionType.CREDIT, new BigDecimal("20.00"), "USD", t2
                    ))));

            // Balance should be sum of all = 60.00 regardless of order
            mockMvc.perform(get("/accounts/" + accountId + "/balance")
                    .contentType(APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(60.00));
        }

        @Test
        @DirtiesContext
        @DisplayName("Transactions ordered by eventTimestamp ASC")
        void outOfOrder_TransactionOrdering() throws Exception {
            String accountId = "acc-ooo-order";
            Instant baseTime = Instant.parse("2024-01-15T10:00:00Z");
            Instant t1 = baseTime;
            Instant t2 = baseTime.plusSeconds(10);
            Instant t3 = baseTime.plusSeconds(20);

            // Submit in order: t3, t1, t2
            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildTransactionRequest(
                            "evt-t3", accountId, Transaction.TransactionType.CREDIT, new BigDecimal("30.00"), "USD", t3
                    ))));

            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildTransactionRequest(
                            "evt-t1", accountId, Transaction.TransactionType.CREDIT, new BigDecimal("10.00"), "USD", t1
                    ))));

            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildTransactionRequest(
                            "evt-t2", accountId, Transaction.TransactionType.CREDIT, new BigDecimal("20.00"), "USD", t2
                    ))));

            // Get account details and verify transaction order
            String response = mockMvc.perform(get("/accounts/" + accountId)
                    .contentType(APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            // Assert order: t1, t2, t3
            int pos1 = response.indexOf("\"eventId\":\"evt-t1\"");
            int pos2 = response.indexOf("\"eventId\":\"evt-t2\"");
            int pos3 = response.indexOf("\"eventId\":\"evt-t3\"");

            assertThat(pos1).isGreaterThan(0).isLessThan(pos2).isLessThan(pos3);
        }
    }

    // ============================================================================
    // Account Creation Tests
    // ============================================================================

    @Nested
    @DisplayName("Account Creation")
    class AccountCreationTests {

        @Test
        @DirtiesContext
        @DisplayName("POST transaction for non-existent account → auto-created")
        void accountCreation_AutoCreated() throws Exception {
            String accountId = "acc-new-001";
            String eventId = "evt-new-001";
            BigDecimal amount = new BigDecimal("250.00");
            Instant eventTimestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS);

            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    eventId, accountId, Transaction.TransactionType.CREDIT, amount, "USD", eventTimestamp
            );

            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.duplicate").value(false));

            // Verify balance reflects the transaction
            mockMvc.perform(get("/accounts/" + accountId + "/balance")
                    .contentType(APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(250.00))
                    .andExpect(jsonPath("$.currency").value("USD"));
        }
    }

    // ============================================================================
    // Account Detail Tests
    // ============================================================================

    @Nested
    @DisplayName("Account Details")
    class AccountDetailTests {

        @Test
        @DirtiesContext
        @DisplayName("GET /accounts/{id} returns detail with transactions ordered by eventTimestamp")
        void accountDetail_CorrectStructure() throws Exception {
            String accountId = "acc-detail-001";
            Instant baseTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);

            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildTransactionRequest(
                            "evt-d1", accountId, Transaction.TransactionType.CREDIT, new BigDecimal("100.00"), "USD", baseTime
                    ))));

            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildTransactionRequest(
                            "evt-d2", accountId, Transaction.TransactionType.DEBIT, new BigDecimal("30.00"), "USD", baseTime.plusSeconds(5)
                    ))));

            mockMvc.perform(get("/accounts/" + accountId)
                    .contentType(APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountId").value(accountId))
                    .andExpect(jsonPath("$.balance").value(70.00))
                    .andExpect(jsonPath("$.currency").value("USD"))
                    .andExpect(jsonPath("$.createdAt").exists())
                    .andExpect(jsonPath("$.updatedAt").exists())
                    .andExpect(jsonPath("$.recentTransactions", org.hamcrest.Matchers.hasSize(2)))
                    .andExpect(jsonPath("$.recentTransactions[0].eventId").value("evt-d1"))
                    .andExpect(jsonPath("$.recentTransactions[1].eventId").value("evt-d2"));
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
            String accountId = "acc-val-001";

            AccountDtos.ApplyTransactionRequest request = new AccountDtos.ApplyTransactionRequest();
            request.setEventId(null);
            request.setAccountId(accountId);
            request.setType(Transaction.TransactionType.CREDIT);
            request.setAmount(BigDecimal.TEN);
            request.setCurrency("USD");
            request.setEventTimestamp(Instant.now().truncatedTo(ChronoUnit.SECONDS));

            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DirtiesContext
        @DisplayName("Zero/negative amount → 400")
        void validation_ZeroAmount() throws Exception {
            String accountId = "acc-val-zero";

            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    "evt-val-zero", accountId, Transaction.TransactionType.CREDIT,
                    BigDecimal.ZERO, "USD", Instant.now().truncatedTo(ChronoUnit.SECONDS)
            );

            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DirtiesContext
        @DisplayName("Missing currency → 400")
        void validation_MissingCurrency() throws Exception {
            String accountId = "acc-val-curr";

            AccountDtos.ApplyTransactionRequest request = new AccountDtos.ApplyTransactionRequest();
            request.setEventId("evt-val-curr");
            request.setAccountId(accountId);
            request.setType(Transaction.TransactionType.CREDIT);
            request.setAmount(BigDecimal.TEN);
            request.setCurrency(null);
            request.setEventTimestamp(Instant.now().truncatedTo(ChronoUnit.SECONDS));

            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ============================================================================
    // Account Not Found Tests
    // ============================================================================

    @Nested
    @DisplayName("Not Found Scenarios")
    class NotFoundTests {

        @Test
        @DirtiesContext
        @DisplayName("GET /balance for unknown account → 404")
        void notFound_UnknownBalance() throws Exception {
            mockMvc.perform(get("/accounts/acc-nonexistent/balance")
                    .contentType(APPLICATION_JSON))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DirtiesContext
        @DisplayName("GET /accounts/{id} for unknown account → 404")
        void notFound_UnknownAccountDetail() throws Exception {
            mockMvc.perform(get("/accounts/acc-nonexistent")
                    .contentType(APPLICATION_JSON))
                    .andExpect(status().isNotFound());
        }
    }

    // ============================================================================
    // Path Variable Mismatch Tests
    // ============================================================================

    @Nested
    @DisplayName("Path/Body Validation")
    class PathBodyValidationTests {

        @Test
        @DirtiesContext
        @DisplayName("accountId in path ≠ body → 400")
        void validation_PathBodyMismatch() throws Exception {
            String pathAccountId = "acc-path";
            String bodyAccountId = "acc-body";

            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    "evt-mismatch", bodyAccountId, Transaction.TransactionType.CREDIT,
                    BigDecimal.TEN, "USD", Instant.now().truncatedTo(ChronoUnit.SECONDS)
            );

            mockMvc.perform(post("/accounts/" + pathAccountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
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
        @DisplayName("X-Trace-Id header echoed in response")
        void traceId_Echoed() throws Exception {
            String traceId = "trace-account-123";
            String accountId = "acc-trace-001";

            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    "evt-trace", accountId, Transaction.TransactionType.CREDIT,
                    BigDecimal.TEN, "USD", Instant.now().truncatedTo(ChronoUnit.SECONDS)
            );

            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .header("X-Trace-Id", traceId)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Trace-Id", traceId));
        }

        @Test
        @DirtiesContext
        @DisplayName("Missing X-Trace-Id → auto-generated and returned")
        void traceId_AutoGenerated() throws Exception {
            String accountId = "acc-trace-auto";

            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    "evt-trace-auto", accountId, Transaction.TransactionType.CREDIT,
                    BigDecimal.TEN, "USD", Instant.now().truncatedTo(ChronoUnit.SECONDS)
            );

            mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("X-Trace-Id"))
                    .andExpect(header().string("X-Trace-Id", org.hamcrest.Matchers.not(org.hamcrest.Matchers.isEmptyString())));
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
        @DisplayName("GET /health → 200, status UP, database UP")
        void health_Up() throws Exception {
            mockMvc.perform(get("/health")
                    .contentType(APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.database").value("UP"));
        }
    }

    // ============================================================================
    // Helper Methods
    // ============================================================================

    private AccountDtos.ApplyTransactionRequest buildTransactionRequest(
            String eventId, String accountId, Transaction.TransactionType type,
            BigDecimal amount, String currency, Instant eventTimestamp) {

        AccountDtos.ApplyTransactionRequest request = new AccountDtos.ApplyTransactionRequest();
        request.setEventId(eventId);
        request.setAccountId(accountId);
        request.setType(type);
        request.setAmount(amount);
        request.setCurrency(currency);
        request.setEventTimestamp(eventTimestamp);
        return request;
    }
}
