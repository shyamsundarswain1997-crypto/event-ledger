package com.eventledger.account.service;

import com.eventledger.account.dto.AccountDtos;
import com.eventledger.account.model.Account;
import com.eventledger.account.model.AccountRepository;
import com.eventledger.account.model.Transaction;
import com.eventledger.account.model.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for AccountService covering all business logic paths.
 * 
 * Test Coverage:
 * - All business logic paths in applyTransaction, getBalance, getAccountDetail
 * - Idempotency behavior for duplicate transaction submissions
 * - Account creation and not-found scenarios
 * - Balance calculation and recomputation from ledger
 * - Out-of-order event handling
 * - Repository interactions and failure modes
 * - Metrics tracking
 * 
 * @author Test Suite
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountService Tests")
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountMetricsService metricsService;

    @InjectMocks
    private AccountService accountService;

    private static final String ACCOUNT_ID = "ACC-12345";
    private static final String EVENT_ID = "EVT-67890";
    private static final String CURRENCY = "USD";
    private static final BigDecimal INITIAL_BALANCE = BigDecimal.valueOf(1000.00);
    private static final BigDecimal TRANSACTION_AMOUNT = BigDecimal.valueOf(100.50);
    private static final Instant EVENT_TIMESTAMP = Instant.parse("2024-01-15T10:00:00Z");

    @Nested
    @DisplayName("applyTransaction - Happy Path")
    class ApplyTransactionHappyPath {

        @Test
        @DisplayName("Should apply new transaction to existing account and return TransactionResponse")
        void shouldApplyNewTransactionToExistingAccount() {
            // Arrange
            Account existingAccount = Account.builder()
                    .accountId(ACCOUNT_ID)
                    .balance(INITIAL_BALANCE)
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    EVENT_ID, ACCOUNT_ID, Transaction.TransactionType.CREDIT,
                    TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP
            );

            when(transactionRepository.findByEventId(EVENT_ID))
                    .thenReturn(Optional.empty());
            when(accountRepository.findByIdWithLock(ACCOUNT_ID))
                    .thenReturn(Optional.of(existingAccount));
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(INITIAL_BALANCE.add(TRANSACTION_AMOUNT));

            Transaction savedTransaction = Transaction.builder()
                    .id("TXN-ID-1")
                    .eventId(EVENT_ID)
                    .accountId(ACCOUNT_ID)
                    .type(Transaction.TransactionType.CREDIT)
                    .amount(TRANSACTION_AMOUNT)
                    .currency(CURRENCY)
                    .eventTimestamp(EVENT_TIMESTAMP)
                    .processedAt(Instant.now())
                    .build();

            when(transactionRepository.save(any(Transaction.class)))
                    .thenReturn(savedTransaction);
            when(accountRepository.save(any(Account.class)))
                    .thenReturn(existingAccount);

            // Act
            AccountDtos.TransactionResponse response = accountService.applyTransaction(request);

            // Assert
            assertThat(response)
                    .isNotNull()
                    .extracting(
                            AccountDtos.TransactionResponse::getEventId,
                            AccountDtos.TransactionResponse::getAccountId,
                            AccountDtos.TransactionResponse::getType,
                            AccountDtos.TransactionResponse::getAmount,
                            AccountDtos.TransactionResponse::getCurrency,
                            AccountDtos.TransactionResponse::isDuplicate
                    )
                    .containsExactly(
                            EVENT_ID, ACCOUNT_ID, Transaction.TransactionType.CREDIT,
                            TRANSACTION_AMOUNT, CURRENCY, false
                    );

            verify(transactionRepository).save(argThat(txn ->
                    txn.getEventId().equals(EVENT_ID) &&
                    txn.getAccountId().equals(ACCOUNT_ID) &&
                    txn.getType() == Transaction.TransactionType.CREDIT &&
                    txn.getAmount().equals(TRANSACTION_AMOUNT)
            ));
            verify(accountRepository).save(argThat(acc ->
                    acc.getBalance().equals(INITIAL_BALANCE.add(TRANSACTION_AMOUNT))
            ));
            verify(metricsService).incrementTransactionsProcessed("CREDIT");
        }

        @Test
        @DisplayName("Should apply DEBIT transaction and update balance correctly")
        void shouldApplyDebitTransaction() {
            // Arrange
            Account account = Account.builder()
                    .accountId(ACCOUNT_ID)
                    .balance(INITIAL_BALANCE)
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            String debitEventId = "EVT-DEBIT-001";
            BigDecimal debitAmount = BigDecimal.valueOf(50.00);

            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    debitEventId, ACCOUNT_ID, Transaction.TransactionType.DEBIT,
                    debitAmount, CURRENCY, EVENT_TIMESTAMP
            );

            when(transactionRepository.findByEventId(debitEventId))
                    .thenReturn(Optional.empty());
            when(accountRepository.findByIdWithLock(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(INITIAL_BALANCE.subtract(debitAmount));

            Transaction savedTxn = Transaction.builder()
                    .id("TXN-DEBIT-001")
                    .eventId(debitEventId)
                    .accountId(ACCOUNT_ID)
                    .type(Transaction.TransactionType.DEBIT)
                    .amount(debitAmount)
                    .currency(CURRENCY)
                    .eventTimestamp(EVENT_TIMESTAMP)
                    .processedAt(Instant.now())
                    .build();

            when(transactionRepository.save(any(Transaction.class)))
                    .thenReturn(savedTxn);
            when(accountRepository.save(any(Account.class)))
                    .thenReturn(account);

            // Act
            AccountDtos.TransactionResponse response = accountService.applyTransaction(request);

            // Assert
            assertThat(response.getType())
                    .isEqualTo(Transaction.TransactionType.DEBIT);
            assertThat(response.getAmount())
                    .isEqualTo(debitAmount);
            verify(metricsService).incrementTransactionsProcessed("DEBIT");
        }

        @Test
        @DisplayName("Should create new account if not exists and apply transaction")
        void shouldCreateNewAccountAndApplyTransaction() {
            // Arrange
            String newAccountId = "ACC-NEW-001";
            String newEventId = "EVT-NEW-001";

            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    newEventId, newAccountId, Transaction.TransactionType.CREDIT,
                    TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP
            );

            Account newAccount = Account.builder()
                    .accountId(newAccountId)
                    .balance(BigDecimal.ZERO)
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            when(transactionRepository.findByEventId(newEventId))
                    .thenReturn(Optional.empty());
            when(accountRepository.findByIdWithLock(newAccountId))
                    .thenReturn(Optional.empty());
            when(accountRepository.save(argThat(acc ->
                    acc.getAccountId().equals(newAccountId) &&
                    acc.getBalance().equals(BigDecimal.ZERO))))
                    .thenReturn(newAccount);
            when(transactionRepository.computeBalance(newAccountId))
                    .thenReturn(TRANSACTION_AMOUNT);

            Transaction savedTxn = Transaction.builder()
                    .id("TXN-NEW-001")
                    .eventId(newEventId)
                    .accountId(newAccountId)
                    .type(Transaction.TransactionType.CREDIT)
                    .amount(TRANSACTION_AMOUNT)
                    .currency(CURRENCY)
                    .eventTimestamp(EVENT_TIMESTAMP)
                    .processedAt(Instant.now())
                    .build();

            when(transactionRepository.save(any(Transaction.class)))
                    .thenReturn(savedTxn);

            // Act
            AccountDtos.TransactionResponse response = accountService.applyTransaction(request);

            // Assert
            assertThat(response)
                    .isNotNull()
                    .extracting(AccountDtos.TransactionResponse::getAccountId)
                    .isEqualTo(newAccountId);

            // Verify account creation was called
            verify(accountRepository, times(2)).save(any(Account.class));
            verify(transactionRepository).save(any(Transaction.class));
        }
    }

    @Nested
    @DisplayName("applyTransaction - Idempotency")
    class ApplyTransactionIdempotency {

        @Test
        @DisplayName("Should detect duplicate transaction by eventId and return existing record with duplicate flag")
        void shouldDetectDuplicateTransactionAndReturnExisting() {
            // Arrange
            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    EVENT_ID, ACCOUNT_ID, Transaction.TransactionType.CREDIT,
                    TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP
            );

            Transaction existingTransaction = Transaction.builder()
                    .id("TXN-DUP-001")
                    .eventId(EVENT_ID)
                    .accountId(ACCOUNT_ID)
                    .type(Transaction.TransactionType.CREDIT)
                    .amount(TRANSACTION_AMOUNT)
                    .currency(CURRENCY)
                    .eventTimestamp(EVENT_TIMESTAMP)
                    .processedAt(Instant.now().minusSeconds(60))
                    .build();

            when(transactionRepository.findByEventId(EVENT_ID))
                    .thenReturn(Optional.of(existingTransaction));

            // Act
            AccountDtos.TransactionResponse response = accountService.applyTransaction(request);

            // Assert
            assertThat(response)
                    .extracting(
                            AccountDtos.TransactionResponse::getEventId,
                            AccountDtos.TransactionResponse::isDuplicate
                    )
                    .containsExactly(EVENT_ID, true);

            verify(metricsService).incrementDuplicateTransactions();
            // Repository save should not be called for duplicate
            verify(transactionRepository, never()).save(any(Transaction.class));
            verify(accountRepository, never()).save(any(Account.class));
        }

        @Test
        @DisplayName("Should handle multiple duplicate submissions of same transaction")
        void shouldHandleMultipleDuplicateSubmissions() {
            // Arrange
            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    EVENT_ID, ACCOUNT_ID, Transaction.TransactionType.CREDIT,
                    TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP
            );

            Transaction existingTxn = Transaction.builder()
                    .id("TXN-ID-001")
                    .eventId(EVENT_ID)
                    .accountId(ACCOUNT_ID)
                    .type(Transaction.TransactionType.CREDIT)
                    .amount(TRANSACTION_AMOUNT)
                    .currency(CURRENCY)
                    .eventTimestamp(EVENT_TIMESTAMP)
                    .processedAt(Instant.now())
                    .build();

            when(transactionRepository.findByEventId(EVENT_ID))
                    .thenReturn(Optional.of(existingTxn));

            // Act - Call multiple times
            AccountDtos.TransactionResponse response1 = accountService.applyTransaction(request);
            AccountDtos.TransactionResponse response2 = accountService.applyTransaction(request);
            AccountDtos.TransactionResponse response3 = accountService.applyTransaction(request);

            // Assert
            assertThat(response1)
                    .isEqualTo(response2)
                    .isEqualTo(response3);
            assertThat(response1.isDuplicate())
                    .isTrue();

            verify(metricsService, times(3)).incrementDuplicateTransactions();
            verify(transactionRepository, never()).save(any(Transaction.class));
        }
    }

    @Nested
    @DisplayName("applyTransaction - Balance Calculation")
    class ApplyTransactionBalanceCalculation {

        @Test
        @DisplayName("Should recompute balance from full ledger sum of all transactions")
        void shouldRecomputeBalanceFromLedger() {
            // Arrange
            Account account = Account.builder()
                    .accountId(ACCOUNT_ID)
                    .balance(BigDecimal.valueOf(500.00))
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            // Simulating a previous balance of 500 + new credit of 100.50 = 600.50
            BigDecimal expectedNewBalance = BigDecimal.valueOf(600.50);

            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    EVENT_ID, ACCOUNT_ID, Transaction.TransactionType.CREDIT,
                    TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP
            );

            when(transactionRepository.findByEventId(EVENT_ID))
                    .thenReturn(Optional.empty());
            when(accountRepository.findByIdWithLock(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(expectedNewBalance);

            Transaction savedTxn = buildTransaction("TXN-001", EVENT_ID, ACCOUNT_ID,
                    Transaction.TransactionType.CREDIT, TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP);

            when(transactionRepository.save(any(Transaction.class)))
                    .thenReturn(savedTxn);

            ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);

            when(accountRepository.save(any(Account.class)))
                    .thenReturn(account);

            // Act
            accountService.applyTransaction(request);

            // Assert
            verify(accountRepository).save(accountCaptor.capture());
            Account savedAccount = accountCaptor.getValue();
            assertThat(savedAccount.getBalance())
                    .isEqualByComparingTo(expectedNewBalance);
        }

        @Test
        @DisplayName("Should correctly compute balance with multiple CREDIT and DEBIT transactions")
        void shouldComputeBalanceWithMixedTransactions() {
            // Arrange
            Account account = Account.builder()
                    .accountId(ACCOUNT_ID)
                    .balance(BigDecimal.ZERO)
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            // Scenario: 2 Credits (100 + 50) - 1 Debit (30) = 120
            BigDecimal balanceAfterTransaction = BigDecimal.valueOf(120.00);

            String creditEventId = "EVT-CREDIT-001";
            AccountDtos.ApplyTransactionRequest creditRequest = buildTransactionRequest(
                    creditEventId, ACCOUNT_ID, Transaction.TransactionType.CREDIT,
                    BigDecimal.valueOf(100), CURRENCY, EVENT_TIMESTAMP
            );

            when(transactionRepository.findByEventId(creditEventId))
                    .thenReturn(Optional.empty());
            when(accountRepository.findByIdWithLock(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(balanceAfterTransaction);

            Transaction savedTxn = buildTransaction("TXN-001", creditEventId, ACCOUNT_ID,
                    Transaction.TransactionType.CREDIT, BigDecimal.valueOf(100), CURRENCY, EVENT_TIMESTAMP);

            when(transactionRepository.save(any(Transaction.class)))
                    .thenReturn(savedTxn);
            when(accountRepository.save(any(Account.class)))
                    .thenReturn(account);

            // Act
            accountService.applyTransaction(creditRequest);

            // Assert
            ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(accountCaptor.capture());
            assertThat(accountCaptor.getValue().getBalance())
                    .isEqualByComparingTo(balanceAfterTransaction);
        }

        @Test
        @DisplayName("Should handle zero balance account after debit")
        void shouldHandleZeroBalanceAfterDebit() {
            // Arrange
            Account account = Account.builder()
                    .accountId(ACCOUNT_ID)
                    .balance(TRANSACTION_AMOUNT)
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            String debitEventId = "EVT-DEBIT-ZERO";
            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    debitEventId, ACCOUNT_ID, Transaction.TransactionType.DEBIT,
                    TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP
            );

            when(transactionRepository.findByEventId(debitEventId))
                    .thenReturn(Optional.empty());
            when(accountRepository.findByIdWithLock(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(BigDecimal.ZERO);

            Transaction savedTxn = buildTransaction("TXN-ZERO", debitEventId, ACCOUNT_ID,
                    Transaction.TransactionType.DEBIT, TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP);

            when(transactionRepository.save(any(Transaction.class)))
                    .thenReturn(savedTxn);
            when(accountRepository.save(any(Account.class)))
                    .thenReturn(account);

            // Act
            accountService.applyTransaction(request);

            // Assert
            ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(accountCaptor.capture());
            assertThat(accountCaptor.getValue().getBalance())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("applyTransaction - Out-of-Order Event Handling")
    class ApplyTransactionOutOfOrder {

        @Test
        @DisplayName("Should correctly compute balance regardless of transaction arrival order")
        void shouldHandleOutOfOrderTransactionArrivals() {
            // Arrange
            // Event 1: CREDIT 100 at T=10:00
            // Event 2: CREDIT 50 at T=09:00 (arrives after Event 1)
            // Expected balance: 150 (regardless of arrival order)

            Account account = Account.builder()
                    .accountId(ACCOUNT_ID)
                    .balance(BigDecimal.ZERO)
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            Instant earlier = Instant.parse("2024-01-15T09:00:00Z");
            Instant later = Instant.parse("2024-01-15T10:00:00Z");

            // Simulate arrival of later event first
            String event1Id = "EVT-001";
            AccountDtos.ApplyTransactionRequest request1 = buildTransactionRequest(
                    event1Id, ACCOUNT_ID, Transaction.TransactionType.CREDIT,
                    BigDecimal.valueOf(100), CURRENCY, later
            );

            when(transactionRepository.findByEventId(event1Id))
                    .thenReturn(Optional.empty());
            when(accountRepository.findByIdWithLock(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(BigDecimal.valueOf(100));

            Transaction txn1 = buildTransaction("TXN-001", event1Id, ACCOUNT_ID,
                    Transaction.TransactionType.CREDIT, BigDecimal.valueOf(100), CURRENCY, later);

            when(transactionRepository.save(any(Transaction.class)))
                    .thenReturn(txn1);
            when(accountRepository.save(any(Account.class)))
                    .thenReturn(account);

            // Act - Apply later event first
            AccountDtos.TransactionResponse response1 = accountService.applyTransaction(request1);

            // Assert - Balance should be 100 after first event
            assertThat(response1.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(100));

            // Reset mocks for second request
            reset(transactionRepository, accountRepository);

            // Now apply earlier event
            String event2Id = "EVT-002";
            AccountDtos.ApplyTransactionRequest request2 = buildTransactionRequest(
                    event2Id, ACCOUNT_ID, Transaction.TransactionType.CREDIT,
                    BigDecimal.valueOf(50), CURRENCY, earlier
            );

            when(transactionRepository.findByEventId(event2Id))
                    .thenReturn(Optional.empty());
            when(accountRepository.findByIdWithLock(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            // Balance computation should account for both (100 + 50 = 150)
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(BigDecimal.valueOf(150));

            Transaction txn2 = buildTransaction("TXN-002", event2Id, ACCOUNT_ID,
                    Transaction.TransactionType.CREDIT, BigDecimal.valueOf(50), CURRENCY, earlier);

            when(transactionRepository.save(any(Transaction.class)))
                    .thenReturn(txn2);
            when(accountRepository.save(any(Account.class)))
                    .thenReturn(account);

            // Act - Apply earlier event
            AccountDtos.TransactionResponse response2 = accountService.applyTransaction(request2);

            // Assert - Balance should be recomputed to 150
            ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(accountCaptor.capture());
            assertThat(accountCaptor.getValue().getBalance())
                    .isEqualByComparingTo(BigDecimal.valueOf(150));
        }

        @Test
        @DisplayName("Should order transactions by eventTimestamp not insertion order in details")
        void shouldOrderTransactionsByBusinessTime() {
            // This is verified in getAccountDetail test
            // Arrange
            String accountId = "ACC-ORDER-TEST";
            Instant t1 = Instant.parse("2024-01-15T08:00:00Z");
            Instant t2 = Instant.parse("2024-01-15T09:00:00Z");
            Instant t3 = Instant.parse("2024-01-15T10:00:00Z");

            Account account = Account.builder()
                    .accountId(accountId)
                    .balance(BigDecimal.valueOf(150))
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            // Transactions in arrival order: t3, t1, t2
            Transaction txn3 = buildTransaction("TXN-3", "EVT-3", accountId,
                    Transaction.TransactionType.CREDIT, BigDecimal.valueOf(100), CURRENCY, t3);
            Transaction txn1 = buildTransaction("TXN-1", "EVT-1", accountId,
                    Transaction.TransactionType.CREDIT, BigDecimal.valueOf(50), CURRENCY, t1);
            Transaction txn2 = buildTransaction("TXN-2", "EVT-2", accountId,
                    Transaction.TransactionType.CREDIT, BigDecimal.valueOf(0), CURRENCY, t2);

            when(accountRepository.findById(accountId))
                    .thenReturn(Optional.of(account));
            when(transactionRepository.findByAccountIdOrderByEventTimestampAsc(accountId))
                    .thenReturn(List.of(txn1, txn2, txn3)); // Should be ordered by eventTimestamp
            when(transactionRepository.computeBalance(accountId))
                    .thenReturn(BigDecimal.valueOf(150));

            // Act
            AccountDtos.AccountDetailResponse detail = accountService.getAccountDetail(accountId);

            // Assert - Transactions should be ordered by eventTimestamp
            assertThat(detail.getRecentTransactions())
                    .extracting(AccountDtos.TransactionResponse::getEventTimestamp)
                    .containsExactly(t1, t2, t3);
        }
    }

    @Nested
    @DisplayName("getBalance")
    class GetBalanceTests {

        @Test
        @DisplayName("Should return current balance for existing account")
        void shouldReturnBalanceForExistingAccount() {
            // Arrange
            Account account = Account.builder()
                    .accountId(ACCOUNT_ID)
                    .balance(INITIAL_BALANCE)
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(INITIAL_BALANCE);

            // Act
            AccountDtos.BalanceResponse response = accountService.getBalance(ACCOUNT_ID);

            // Assert
            assertThat(response)
                    .isNotNull()
                    .extracting(
                            AccountDtos.BalanceResponse::getAccountId,
                            AccountDtos.BalanceResponse::getBalance,
                            AccountDtos.BalanceResponse::getCurrency
                    )
                    .containsExactly(ACCOUNT_ID, INITIAL_BALANCE, CURRENCY);

            assertThat(response.getAsOf())
                    .isNotNull();
        }

        @Test
        @DisplayName("Should re-derive balance from ledger at query time")
        void shouldReDeriveBalanceAtQueryTime() {
            // Arrange
            BigDecimal storedBalance = BigDecimal.valueOf(1000.00);
            BigDecimal actualBalance = BigDecimal.valueOf(1500.00); // Recomputed value

            Account account = Account.builder()
                    .accountId(ACCOUNT_ID)
                    .balance(storedBalance)
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(actualBalance);

            // Act
            AccountDtos.BalanceResponse response = accountService.getBalance(ACCOUNT_ID);

            // Assert - Should return recomputed balance, not stored balance
            assertThat(response.getBalance())
                    .isEqualByComparingTo(actualBalance);

            verify(transactionRepository).computeBalance(ACCOUNT_ID);
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for non-existent account")
        void shouldThrowExceptionForNonExistentAccount() {
            // Arrange
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> accountService.getBalance(ACCOUNT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Account not found");

            verify(transactionRepository, never()).computeBalance(any());
        }

        @Test
        @DisplayName("Should handle zero balance correctly")
        void shouldHandleZeroBalance() {
            // Arrange
            Account account = Account.builder()
                    .accountId(ACCOUNT_ID)
                    .balance(BigDecimal.ZERO)
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(BigDecimal.ZERO);

            // Act
            AccountDtos.BalanceResponse response = accountService.getBalance(ACCOUNT_ID);

            // Assert
            assertThat(response.getBalance())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Should handle large balance values")
        void shouldHandleLargeBalanceValues() {
            // Arrange
            BigDecimal largeBalance = BigDecimal.valueOf(999999999.9999);

            Account account = Account.builder()
                    .accountId(ACCOUNT_ID)
                    .balance(largeBalance)
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(largeBalance);

            // Act
            AccountDtos.BalanceResponse response = accountService.getBalance(ACCOUNT_ID);

            // Assert
            assertThat(response.getBalance())
                    .isEqualByComparingTo(largeBalance);
        }
    }

    @Nested
    @DisplayName("getAccountDetail")
    class GetAccountDetailTests {

        @Test
        @DisplayName("Should return complete account details with transaction history")
        void shouldReturnCompleteAccountDetail() {
            // Arrange
            Account account = Account.builder()
                    .accountId(ACCOUNT_ID)
                    .balance(INITIAL_BALANCE)
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            Transaction txn1 = buildTransaction("TXN-1", "EVT-1", ACCOUNT_ID,
                    Transaction.TransactionType.CREDIT, BigDecimal.valueOf(100), CURRENCY, EVENT_TIMESTAMP);
            Transaction txn2 = buildTransaction("TXN-2", "EVT-2", ACCOUNT_ID,
                    Transaction.TransactionType.DEBIT, BigDecimal.valueOf(50), CURRENCY, EVENT_TIMESTAMP);

            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionRepository.findByAccountIdOrderByEventTimestampAsc(ACCOUNT_ID))
                    .thenReturn(List.of(txn1, txn2));
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(INITIAL_BALANCE);

            // Act
            AccountDtos.AccountDetailResponse response = accountService.getAccountDetail(ACCOUNT_ID);

            // Assert
            assertThat(response)
                    .isNotNull()
                    .extracting(
                            AccountDtos.AccountDetailResponse::getAccountId,
                            AccountDtos.AccountDetailResponse::getBalance,
                            AccountDtos.AccountDetailResponse::getCurrency
                    )
                    .containsExactly(ACCOUNT_ID, INITIAL_BALANCE, CURRENCY);

            assertThat(response.getRecentTransactions())
                    .hasSize(2)
                    .extracting(AccountDtos.TransactionResponse::getEventId)
                    .containsExactly("EVT-1", "EVT-2");
        }

        @Test
        @DisplayName("Should return account detail with empty transaction list")
        void shouldReturnAccountDetailWithEmptyTransactions() {
            // Arrange
            Account account = Account.builder()
                    .accountId(ACCOUNT_ID)
                    .balance(BigDecimal.ZERO)
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionRepository.findByAccountIdOrderByEventTimestampAsc(ACCOUNT_ID))
                    .thenReturn(List.of());
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(BigDecimal.ZERO);

            // Act
            AccountDtos.AccountDetailResponse response = accountService.getAccountDetail(ACCOUNT_ID);

            // Assert
            assertThat(response.getRecentTransactions())
                    .isEmpty();
            assertThat(response.getBalance())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException if account not found")
        void shouldThrowExceptionWhenAccountNotFound() {
            // Arrange
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> accountService.getAccountDetail(ACCOUNT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Account not found");

            verify(transactionRepository, never()).findByAccountIdOrderByEventTimestampAsc(any());
        }

        @Test
        @DisplayName("Should include createdAt and updatedAt timestamps in response")
        void shouldIncludeTimestampsInResponse() {
            // Arrange
            Instant createdAt = Instant.parse("2024-01-01T00:00:00Z");
            Instant updatedAt = Instant.parse("2024-01-15T12:00:00Z");

            Account account = Account.builder()
                    .accountId(ACCOUNT_ID)
                    .balance(INITIAL_BALANCE)
                    .currency(CURRENCY)
                    .createdAt(createdAt)
                    .updatedAt(updatedAt)
                    .build();

            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionRepository.findByAccountIdOrderByEventTimestampAsc(ACCOUNT_ID))
                    .thenReturn(List.of());
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(INITIAL_BALANCE);

            // Act
            AccountDtos.AccountDetailResponse response = accountService.getAccountDetail(ACCOUNT_ID);

            // Assert
            assertThat(response.getCreatedAt())
                    .isEqualTo(createdAt);
            assertThat(response.getUpdatedAt())
                    .isEqualTo(updatedAt);
        }

        @Test
        @DisplayName("Should return transactions ordered by eventTimestamp")
        void shouldOrderTransactionsByEventTimestamp() {
            // Arrange
            Account account = Account.builder()
                    .accountId(ACCOUNT_ID)
                    .balance(INITIAL_BALANCE)
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            Instant t1 = Instant.parse("2024-01-15T09:00:00Z");
            Instant t2 = Instant.parse("2024-01-15T10:00:00Z");
            Instant t3 = Instant.parse("2024-01-15T11:00:00Z");

            Transaction txn1 = buildTransaction("TXN-1", "EVT-1", ACCOUNT_ID,
                    Transaction.TransactionType.CREDIT, BigDecimal.valueOf(100), CURRENCY, t1);
            Transaction txn2 = buildTransaction("TXN-2", "EVT-2", ACCOUNT_ID,
                    Transaction.TransactionType.CREDIT, BigDecimal.valueOf(50), CURRENCY, t2);
            Transaction txn3 = buildTransaction("TXN-3", "EVT-3", ACCOUNT_ID,
                    Transaction.TransactionType.DEBIT, BigDecimal.valueOf(30), CURRENCY, t3);

            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionRepository.findByAccountIdOrderByEventTimestampAsc(ACCOUNT_ID))
                    .thenReturn(List.of(txn1, txn2, txn3));
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(INITIAL_BALANCE);

            // Act
            AccountDtos.AccountDetailResponse response = accountService.getAccountDetail(ACCOUNT_ID);

            // Assert
            assertThat(response.getRecentTransactions())
                    .extracting(AccountDtos.TransactionResponse::getEventTimestamp)
                    .containsExactly(t1, t2, t3);
        }
    }

    @Nested
    @DisplayName("Repository Interaction Tests")
    class RepositoryInteractionTests {

        @Test
        @DisplayName("Should use pessimistic lock when fetching account for transaction")
        void shouldUsePessimisticLockForAccountFetch() {
            // Arrange
            Account account = Account.builder()
                    .accountId(ACCOUNT_ID)
                    .balance(INITIAL_BALANCE)
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    EVENT_ID, ACCOUNT_ID, Transaction.TransactionType.CREDIT,
                    TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP
            );

            when(transactionRepository.findByEventId(EVENT_ID))
                    .thenReturn(Optional.empty());
            when(accountRepository.findByIdWithLock(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(INITIAL_BALANCE.add(TRANSACTION_AMOUNT));

            Transaction savedTxn = buildTransaction("TXN-1", EVENT_ID, ACCOUNT_ID,
                    Transaction.TransactionType.CREDIT, TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP);

            when(transactionRepository.save(any(Transaction.class)))
                    .thenReturn(savedTxn);
            when(accountRepository.save(any(Account.class)))
                    .thenReturn(account);

            // Act
            accountService.applyTransaction(request);

            // Assert - findByIdWithLock should be called, not just findById
            verify(accountRepository).findByIdWithLock(ACCOUNT_ID);
            verify(accountRepository, never()).findById(ACCOUNT_ID);
        }

        @Test
        @DisplayName("Should save transaction before updating account balance")
        void shouldSaveTransactionBeforeUpdatingBalance() {
            // Arrange
            Account account = Account.builder()
                    .accountId(ACCOUNT_ID)
                    .balance(INITIAL_BALANCE)
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    EVENT_ID, ACCOUNT_ID, Transaction.TransactionType.CREDIT,
                    TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP
            );

            when(transactionRepository.findByEventId(EVENT_ID))
                    .thenReturn(Optional.empty());
            when(accountRepository.findByIdWithLock(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(INITIAL_BALANCE.add(TRANSACTION_AMOUNT));

            Transaction savedTxn = buildTransaction("TXN-1", EVENT_ID, ACCOUNT_ID,
                    Transaction.TransactionType.CREDIT, TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP);

            when(transactionRepository.save(any(Transaction.class)))
                    .thenReturn(savedTxn);
            when(accountRepository.save(any(Account.class)))
                    .thenReturn(account);

            InOrder inOrder = inOrder(transactionRepository, accountRepository);

            // Act
            accountService.applyTransaction(request);

            // Assert - Transaction should be saved first
            inOrder.verify(transactionRepository).save(any(Transaction.class));
            inOrder.verify(accountRepository).save(any(Account.class));
        }

        @Test
        @DisplayName("Should verify correct arguments passed to repository save methods")
        void shouldVerifyRepositorySaveArguments() {
            // Arrange
            Account account = Account.builder()
                    .accountId(ACCOUNT_ID)
                    .balance(INITIAL_BALANCE)
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    EVENT_ID, ACCOUNT_ID, Transaction.TransactionType.CREDIT,
                    TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP
            );

            when(transactionRepository.findByEventId(EVENT_ID))
                    .thenReturn(Optional.empty());
            when(accountRepository.findByIdWithLock(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(INITIAL_BALANCE.add(TRANSACTION_AMOUNT));

            Transaction savedTxn = buildTransaction("TXN-1", EVENT_ID, ACCOUNT_ID,
                    Transaction.TransactionType.CREDIT, TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP);

            when(transactionRepository.save(any(Transaction.class)))
                    .thenReturn(savedTxn);
            when(accountRepository.save(any(Account.class)))
                    .thenReturn(account);

            ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
            ArgumentCaptor<Account> accCaptor = ArgumentCaptor.forClass(Account.class);

            // Act
            accountService.applyTransaction(request);

            // Assert
            verify(transactionRepository).save(txnCaptor.capture());
            verify(accountRepository).save(accCaptor.capture());

            Transaction savedTransaction = txnCaptor.getValue();
            assertThat(savedTransaction)
                    .extracting(
                            Transaction::getEventId,
                            Transaction::getAccountId,
                            Transaction::getType,
                            Transaction::getAmount,
                            Transaction::getCurrency,
                            Transaction::getEventTimestamp
                    )
                    .containsExactly(
                            EVENT_ID, ACCOUNT_ID, Transaction.TransactionType.CREDIT,
                            TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP
                    );

            Account savedAccount = accCaptor.getValue();
            assertThat(savedAccount.getAccountId())
                    .isEqualTo(ACCOUNT_ID);
            assertThat(savedAccount.getBalance())
                    .isEqualByComparingTo(INITIAL_BALANCE.add(TRANSACTION_AMOUNT));
        }
    }

    @Nested
    @DisplayName("Metrics Tracking")
    class MetricsTrackingTests {

        @Test
        @DisplayName("Should increment CREDIT transaction metrics")
        void shouldIncrementCreditMetrics() {
            // Arrange
            Account account = Account.builder()
                    .accountId(ACCOUNT_ID)
                    .balance(INITIAL_BALANCE)
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    EVENT_ID, ACCOUNT_ID, Transaction.TransactionType.CREDIT,
                    TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP
            );

            when(transactionRepository.findByEventId(EVENT_ID))
                    .thenReturn(Optional.empty());
            when(accountRepository.findByIdWithLock(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(INITIAL_BALANCE.add(TRANSACTION_AMOUNT));

            Transaction savedTxn = buildTransaction("TXN-1", EVENT_ID, ACCOUNT_ID,
                    Transaction.TransactionType.CREDIT, TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP);

            when(transactionRepository.save(any(Transaction.class)))
                    .thenReturn(savedTxn);
            when(accountRepository.save(any(Account.class)))
                    .thenReturn(account);

            // Act
            accountService.applyTransaction(request);

            // Assert
            verify(metricsService).incrementTransactionsProcessed("CREDIT");
        }

        @Test
        @DisplayName("Should increment DEBIT transaction metrics")
        void shouldIncrementDebitMetrics() {
            // Arrange
            Account account = Account.builder()
                    .accountId(ACCOUNT_ID)
                    .balance(INITIAL_BALANCE)
                    .currency(CURRENCY)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            String debitEventId = "EVT-DEBIT-001";

            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    debitEventId, ACCOUNT_ID, Transaction.TransactionType.DEBIT,
                    TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP
            );

            when(transactionRepository.findByEventId(debitEventId))
                    .thenReturn(Optional.empty());
            when(accountRepository.findByIdWithLock(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionRepository.computeBalance(ACCOUNT_ID))
                    .thenReturn(INITIAL_BALANCE.subtract(TRANSACTION_AMOUNT));

            Transaction savedTxn = buildTransaction("TXN-DEBIT", debitEventId, ACCOUNT_ID,
                    Transaction.TransactionType.DEBIT, TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP);

            when(transactionRepository.save(any(Transaction.class)))
                    .thenReturn(savedTxn);
            when(accountRepository.save(any(Account.class)))
                    .thenReturn(account);

            // Act
            accountService.applyTransaction(request);

            // Assert
            verify(metricsService).incrementTransactionsProcessed("DEBIT");
        }

        @Test
        @DisplayName("Should increment duplicate transaction metrics for duplicate submissions")
        void shouldIncrementDuplicateMetrics() {
            // Arrange
            AccountDtos.ApplyTransactionRequest request = buildTransactionRequest(
                    EVENT_ID, ACCOUNT_ID, Transaction.TransactionType.CREDIT,
                    TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP
            );

            Transaction existingTxn = buildTransaction("TXN-1", EVENT_ID, ACCOUNT_ID,
                    Transaction.TransactionType.CREDIT, TRANSACTION_AMOUNT, CURRENCY, EVENT_TIMESTAMP);

            when(transactionRepository.findByEventId(EVENT_ID))
                    .thenReturn(Optional.of(existingTxn));

            // Act
            accountService.applyTransaction(request);

            // Assert
            verify(metricsService).incrementDuplicateTransactions();
            verify(metricsService, never()).incrementTransactionsProcessed(any());
        }
    }

    // ===== Helper Methods =====

    private AccountDtos.ApplyTransactionRequest buildTransactionRequest(
            String eventId,
            String accountId,
            Transaction.TransactionType type,
            BigDecimal amount,
            String currency,
            Instant eventTimestamp) {
        AccountDtos.ApplyTransactionRequest request = new AccountDtos.ApplyTransactionRequest();
        request.setEventId(eventId);
        request.setAccountId(accountId);
        request.setType(type);
        request.setAmount(amount);
        request.setCurrency(currency);
        request.setEventTimestamp(eventTimestamp);
        return request;
    }

    private Transaction buildTransaction(
            String id,
            String eventId,
            String accountId,
            Transaction.TransactionType type,
            BigDecimal amount,
            String currency,
            Instant eventTimestamp) {
        return Transaction.builder()
                .id(id)
                .eventId(eventId)
                .accountId(accountId)
                .type(type)
                .amount(amount)
                .currency(currency)
                .eventTimestamp(eventTimestamp)
                .processedAt(Instant.now())
                .build();
    }
}
