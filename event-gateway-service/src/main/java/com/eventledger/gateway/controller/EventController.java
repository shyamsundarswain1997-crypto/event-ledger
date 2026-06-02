package com.eventledger.gateway.controller;

import com.eventledger.gateway.dto.GatewayDtos;
import com.eventledger.gateway.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Public-facing REST API for the Event Ledger Gateway.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    /**
     * Submit a transaction event.
     *
     * <p>Returns 200 OK for both new and duplicate events. The {@code duplicate}
     * flag in the body allows clients to distinguish re-submissions from new events
     * without relying on a different status code — this is consistent with how major
     * payment APIs (Stripe, PayPal) handle idempotency.</p>
     */
    @PostMapping("/events")
    public ResponseEntity<GatewayDtos.EventResponse> submitEvent(
            @Valid @RequestBody GatewayDtos.SubmitEventRequest request) {
        log.info("Received POST /events eventId={} accountId={}", request.getEventId(), request.getAccountId());
        GatewayDtos.EventResponse response = eventService.submitEvent(request);
        HttpStatus status = response.isDuplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * Get a single event by its ID.
     * Works independently of Account Service availability (reads from Gateway's local store).
     */
    @GetMapping("/events/{id}")
    public ResponseEntity<GatewayDtos.EventResponse> getEvent(@PathVariable String id) {
        try {
            return ResponseEntity.ok(eventService.getEvent(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * List all events for an account, ordered chronologically by event timestamp.
     * Works independently of Account Service availability.
     */
    @GetMapping("/events")
    public ResponseEntity<List<GatewayDtos.EventResponse>> listEvents(
            @RequestParam(name = "account") String accountId) {
        log.debug("GET /events?account={}", accountId);
        return ResponseEntity.ok(eventService.listEventsByAccount(accountId));
    }
}
