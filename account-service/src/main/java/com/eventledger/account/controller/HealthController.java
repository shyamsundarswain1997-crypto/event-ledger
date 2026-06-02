package com.eventledger.account.controller;

import com.eventledger.account.dto.AccountDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/health")
    public ResponseEntity<AccountDtos.HealthResponse> health() {
        String dbStatus = checkDatabase();
        boolean healthy = "UP".equals(dbStatus);

        AccountDtos.HealthResponse response = AccountDtos.HealthResponse.builder()
                .status(healthy ? "UP" : "DEGRADED")
                .service("account-service")
                .timestamp(Instant.now())
                .database(dbStatus)
                .build();

        return healthy
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(503).body(response);
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
}
