package com.eventledger.account.controller;

import com.eventledger.account.dto.AccountDtos;
import com.eventledger.account.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal REST API for the Account Service.
 * This controller is intentionally NOT exposed to external clients —
 * it is only called by the Event Gateway.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    /**
     * Apply a transaction to an account.
     * Returns 200 for both new and duplicate events (idempotent endpoint).
     * The {@code duplicate} flag in the response body communicates whether
     * this was a re-submission.
     */
    @PostMapping("/accounts/{accountId}/transactions")
    public ResponseEntity<AccountDtos.TransactionResponse> applyTransaction(
            @PathVariable String accountId,
            @Valid @RequestBody AccountDtos.ApplyTransactionRequest request) {

        // Ensure path variable and body are consistent
        if (!accountId.equals(request.getAccountId())) {
            return ResponseEntity.badRequest().build();
        }

        AccountDtos.TransactionResponse response = accountService.applyTransaction(request);
        log.info("Transaction response: eventId={} duplicate={}", response.getEventId(), response.isDuplicate());
        return ResponseEntity.ok(response);
    }

    /**
     * Get the current balance for an account.
     */
    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<AccountDtos.BalanceResponse> getBalance(@PathVariable String accountId) {
        log.debug("Balance query for accountId={}", accountId);
        return ResponseEntity.ok(accountService.getBalance(accountId));
    }

    /**
     * Get account details including chronologically ordered transactions.
     */
    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<AccountDtos.AccountDetailResponse> getAccount(@PathVariable String accountId) {
        log.debug("Account detail query for accountId={}", accountId);
        return ResponseEntity.ok(accountService.getAccountDetail(accountId));
    }
}
