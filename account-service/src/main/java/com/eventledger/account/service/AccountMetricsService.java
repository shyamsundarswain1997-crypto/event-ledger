package com.eventledger.account.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralizes all custom metric definitions for the Account Service.
 * Using constructor injection ensures metrics are registered at startup,
 * not lazily — this avoids gaps in dashboards during low-traffic periods.
 */
@Slf4j
@Service
public class AccountMetricsService {

    private final Counter transactionsProcessedCredit;
    private final Counter transactionsProcessedDebit;
    private final Counter duplicateTransactions;
    private final Timer transactionProcessingTimer;

    public AccountMetricsService(MeterRegistry registry) {
        this.transactionsProcessedCredit = Counter.builder("account.transactions.processed")
                .tag("type", "CREDIT")
                .description("Number of CREDIT transactions successfully applied")
                .register(registry);

        this.transactionsProcessedDebit = Counter.builder("account.transactions.processed")
                .tag("type", "DEBIT")
                .description("Number of DEBIT transactions successfully applied")
                .register(registry);

        this.duplicateTransactions = Counter.builder("account.transactions.duplicates")
                .description("Number of duplicate transaction submissions rejected")
                .register(registry);

        this.transactionProcessingTimer = Timer.builder("account.transaction.processing.duration")
                .description("Time taken to process and persist a transaction")
                .register(registry);
    }

    public void incrementTransactionsProcessed(String type) {
        if ("CREDIT".equals(type)) {
            transactionsProcessedCredit.increment();
        } else {
            transactionsProcessedDebit.increment();
        }
    }

    public void incrementDuplicateTransactions() {
        duplicateTransactions.increment();
    }

    public Timer getTransactionProcessingTimer() {
        return transactionProcessingTimer;
    }
}
