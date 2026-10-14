package com.auravision.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Lightweight, secret-free readiness endpoint for Render and uptime monitoring.
 * It deliberately reports only whether the application and its database are reachable.
 */
@RestController
public class HealthController {
    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record HealthResponse(String status, String database) {}

    @GetMapping("/api/health")
    public ResponseEntity<HealthResponse> health() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(2)) {
                return ResponseEntity.ok(new HealthResponse("ok", "connected"));
            }
        } catch (Exception ignored) {
            // Never expose connection strings, usernames, or provider exception details publicly.
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new HealthResponse("degraded", "unavailable"));
    }
}
