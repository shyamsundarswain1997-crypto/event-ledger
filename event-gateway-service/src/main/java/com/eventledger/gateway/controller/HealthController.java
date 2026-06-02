package com.eventledger.gateway.controller;

import com.eventledger.gateway.dto.GatewayDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;

    @Value("${account-service.base-url}")
    private String accountServiceBaseUrl;

    @GetMapping("/health")
    public ResponseEntity<GatewayDtos.HealthResponse> health() {
        String dbStatus = checkDatabase();
        String accountServiceStatus = checkAccountService();

        // Gateway is healthy if its own DB is up; Account Service being down is a degraded state
        boolean healthy = "UP".equals(dbStatus);
        String overallStatus = healthy
                ? ("UP".equals(accountServiceStatus) ? "UP" : "DEGRADED")
                : "DOWN";

        GatewayDtos.HealthResponse response = GatewayDtos.HealthResponse.builder()
                .status(overallStatus)
                .service("event-gateway")
                .timestamp(Instant.now())
                .database(dbStatus)
                .accountService(accountServiceStatus)
                .build();

        return "DOWN".equals(overallStatus)
                ? ResponseEntity.status(503).body(response)
                : ResponseEntity.ok(response);
    }

    private String checkDatabase() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return "UP";
        } catch (Exception e) {
            log.error("Database health check failed", e);
            return "DOWN";
        }
    }

    private String checkAccountService() {
        try {
            restTemplate.getForObject(accountServiceBaseUrl + "/health", String.class);
            return "UP";
        } catch (Exception e) {
            log.warn("Account Service health check failed: {}", e.getMessage());
            return "DOWN";
        }
    }
}
