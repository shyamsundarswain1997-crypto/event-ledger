package com.eventledger.gateway.client;

import com.eventledger.gateway.dto.GatewayDtos;
import com.eventledger.gateway.exception.AccountServiceException;
import com.eventledger.gateway.exception.AccountServiceUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * HTTP client for the Account Service.
 *
 * <h3>Resiliency Strategy: Circuit Breaker + Retry with Backoff</h3>
 *
 * <p>We combine two Resilience4j patterns:
 * <ul>
 *   <li><b>Circuit Breaker</b> — After a threshold of failures, the circuit opens and calls
 *       fail fast without hitting the Account Service. This prevents the Gateway's thread pool
 *       from being exhausted waiting on a consistently unavailable downstream. The circuit
 *       transitions to HALF_OPEN after a wait, allowing one probe request to check recovery.</li>
 *   <li><b>Retry with exponential backoff</b> — For transient failures (e.g., a brief network
 *       hiccup or a single timeout), we retry up to 3 times with increasing delays. This handles
 *       the common case of momentary unavailability without surfacing errors to the client.</li>
 * </ul>
 *
 * <p>The circuit breaker wraps the retry so that if all retries fail, it counts as one failure
 * against the breaker. This avoids inflating the failure count during retry storms.</p>
 *
 * <p>Why not Bulkhead? A bulkhead would limit concurrent calls but would still let threads wait.
 * Since the Account Service has strict SLA requirements and we want fast failure with a clear
 * 503 error, the circuit breaker gives us better UX during outages.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountServiceClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${account-service.base-url}")
    private String baseUrl;

    /**
     * Applies a transaction to the Account Service.
     *
     * @return the response body map on success
     * @throws AccountServiceUnavailableException when the circuit is open or retries exhausted
     */
    @CircuitBreaker(name = "accountService", fallbackMethod = "applyTransactionFallback")
    @Retry(name = "accountService")
    public Map<String, Object> applyTransaction(String accountId, Map<String, Object> requestBody) {
        String url = baseUrl + "/accounts/" + accountId + "/transactions";
        log.debug("Calling Account Service POST {} for accountId={}", url, accountId);

        HttpHeaders headers = buildHeaders();
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            log.debug("Account Service responded with status={}", response.getStatusCode());
            return response.getBody();
        } catch (HttpClientErrorException e) {
            // 4xx from the account service are not transient — don't retry these
            log.warn("Account Service returned client error status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new AccountServiceException("Account Service rejected request: " + e.getMessage(), e);
        }
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "getBalanceFallback")
    @Retry(name = "accountService")
    public Map<String, Object> getBalance(String accountId) {
        String url = baseUrl + "/accounts/" + accountId + "/balance";
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(buildHeaders()), Map.class);
        return response.getBody();
    }

    // -----------------------------------------------------------------------
    // Fallback methods — invoked when the circuit is open or retries exhausted
    // -----------------------------------------------------------------------

    @SuppressWarnings("unused")
    private Map<String, Object> applyTransactionFallback(String accountId, Map<String, Object> requestBody, Throwable t) {
        log.warn("Circuit breaker OPEN or retries exhausted for applyTransaction accountId={} cause={}",
                accountId, t.getMessage());
        throw new AccountServiceUnavailableException(
                "Account Service is currently unavailable. Please try again later.", t);
    }

    @SuppressWarnings("unused")
    private Map<String, Object> getBalanceFallback(String accountId, Throwable t) {
        log.warn("Circuit breaker OPEN or retries exhausted for getBalance accountId={} cause={}",
                accountId, t.getMessage());
        throw new AccountServiceUnavailableException(
                "Account Service is currently unavailable. Balance cannot be retrieved.", t);
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Propagate the trace ID so both services share a single trace
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            headers.set("X-Trace-Id", traceId);
        }
        return headers;
    }
}
