package com.eventledger.account.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    Optional<Transaction> findByEventId(String eventId);

    boolean existsByEventId(String eventId);

    /**
     * Returns transactions ordered by business timestamp (event_timestamp).
     * This ensures chronological ordering regardless of arrival order.
     */
    @Query("SELECT t FROM Transaction t WHERE t.accountId = :accountId ORDER BY t.eventTimestamp ASC")
    List<Transaction> findByAccountIdOrderByEventTimestampAsc(String accountId);

    /**
     * Computes balance as: SUM(CREDIT) - SUM(DEBIT).
     * Using COALESCE to handle accounts with only CREDITs or only DEBITs.
     */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE 0 END), 0)
                 - COALESCE(SUM(CASE WHEN t.type = 'DEBIT'  THEN t.amount ELSE 0 END), 0)
            FROM Transaction t WHERE t.accountId = :accountId
            """)
    java.math.BigDecimal computeBalance(String accountId);
}
