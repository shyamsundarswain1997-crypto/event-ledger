package com.eventledger.gateway.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventRepository extends JpaRepository<EventRecord, String> {

    boolean existsByEventId(String eventId);

    Optional<EventRecord> findByEventId(String eventId);

    /**
     * Returns events for an account ordered by business timestamp.
     * This is the chronological view required by the spec.
     */
    List<EventRecord> findByAccountIdOrderByEventTimestampAsc(String accountId);
}
