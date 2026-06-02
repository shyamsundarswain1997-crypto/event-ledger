package com.eventledger.account.service;

import com.eventledger.account.dto.AccountDtos;
import com.eventledger.account.exception.DuplicateTransactionException;
import com.eventledger.account.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    /**
     * Applies a transaction to an account.
     *
     * <p>Idempotency is enforced at this layer as well (defense-in-depth):
     * if the eventId has already been processed, we return the existing record
     * without modifying the balance.</p>
     *
     * <p>Out-of-order tolerance: balance is always recomputed from the full
     * transaction ledger, so ordering of arrival does not affect correctness.
     * The balance field on the Account entity reflects the sum of all processed
     * transactions regardless of insertion order.</p>
     */
    @Transactional
    public AccountDtos.TransactionResponse applyTransaction(AccountDtos.ApplyTransactionRequest request) {
        log.info("Applying transaction eventId={} accountId={} type={} amount={}",
                request.getEventId(), request.getAccountId(), request.getType(), request.getAmount());

        // Idempotency check — return existing record on duplicate submission
        Optional<Transaction> existing = transactionRepository.findByEventId(request.getEventId());
        if (existing.isPresent()) {
            log.info("Duplicate transaction detected eventId={} — returning existing record", request.getEventId());
            return toTransactionResponse(existing.get(), true);
        }

        // Get or create account with a pessimistic lock to avoid concurrent balance updates
        Account account = accountRepository.findByIdWithLock(request.getAccountId())
                .orElseGet(() -> createAccount(request.getAccountId(), request.getCurrency()));

        // Persist the transaction record first
        Transaction transaction = Transaction.builder()
                .eventId(request.getEventId())
                .accountId(request.getAccountId())
                .type(request.getType())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .eventTimestamp(request.getEventTimestamp())
                .processedAt(Instant.now())
                .build();
        transactionRepository.save(transaction);

        // Recompute balance from the ledger — this is the key to out-of-order correctness.
        // We never do "balance += amount"; we always derive from the full history.
        BigDecimal newBalance = transactionRepository.computeBalance(request.getAccountId());
        account.setBalance(newBalance);
        accountRepository.save(account);

        log.info("Transaction applied successfully eventId={} newBalance={}", request.getEventId(), newBalance);
        return toTransactionResponse(transaction, false);
    }

    /**
     * Returns the current balance for an account.
     * Balance is always the authoritative ledger sum, never a cached value that can drift.
     */
    @Transactional(readOnly = true)
    public AccountDtos.BalanceResponse getBalance(String accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        // Re-derive balance at query time for consistency
        BigDecimal balance = transactionRepository.computeBalance(accountId);

        return AccountDtos.BalanceResponse.builder()
                .accountId(accountId)
                .balance(balance)
                .currency(account.getCurrency())
                .asOf(Instant.now())
                .build();
    }

    /**
     * Returns account details including a chronologically ordered transaction list.
     */
    @Transactional(readOnly = true)
    public AccountDtos.AccountDetailResponse getAccountDetail(String accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        // Transactions ordered by eventTimestamp (business time), not insertion time
        List<Transaction> transactions = transactionRepository
                .findByAccountIdOrderByEventTimestampAsc(accountId);

        BigDecimal balance = transactionRepository.computeBalance(accountId);

        List<AccountDtos.TransactionResponse> txResponses = transactions.stream()
                .map(t -> toTransactionResponse(t, false))
                .toList();

        return AccountDtos.AccountDetailResponse.builder()
                .accountId(accountId)
                .balance(balance)
                .currency(account.getCurrency())
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .recentTransactions(txResponses)
                .build();
    }

    private Account createAccount(String accountId, String currency) {
        log.info("Creating new account accountId={} currency={}", accountId, currency);
        Account account = Account.builder()
                .accountId(accountId)
                .balance(BigDecimal.ZERO)
                .currency(currency)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return accountRepository.save(account);
    }

    private AccountDtos.TransactionResponse toTransactionResponse(Transaction t, boolean duplicate) {
        return AccountDtos.TransactionResponse.builder()
                .id(t.getId())
                .eventId(t.getEventId())
                .accountId(t.getAccountId())
                .type(t.getType())
                .amount(t.getAmount())
                .currency(t.getCurrency())
                .eventTimestamp(t.getEventTimestamp())
                .processedAt(t.getProcessedAt())
                .duplicate(duplicate)
                .build();
    }
}
