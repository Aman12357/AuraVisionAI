package com.auravision.web;

import com.auravision.model.AccountUser;
import com.auravision.repo.AccountUserRepository;
import com.auravision.service.GenerationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Admin access is configured only through Render environment variables.
 * Never add admin credentials to source control or expose password hashes/session tokens.
 */
@RestController
@RequiredArgsConstructor
public class AdminController {
    private static final String ADMIN_SESSION_KEY = "AURA_ADMIN_AUTHENTICATED";

    private final AccountUserRepository users;
    private final GenerationService generations;

    @Value("${AURA_ADMIN_EMAIL:}") private String adminEmail;
    @Value("${AURA_ADMIN_PASSWORD:}") private String adminPassword;

    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(max = 128) String password) {}
    public record UserSummary(Long id, String email, Instant createdAt, String plan,
                             int imagesLeftToday, int videosLeftToday) {}

    @GetMapping("/admin/login")
    public ResponseEntity<Void> loginPage() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", "/admin/login.html").build();
    }

    @GetMapping("/admin")
    public ResponseEntity<Void> adminHome() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", "/admin/dashboard.html").build();
    }

    @PostMapping("/api/admin/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Admin login is not configured. Set AURA_ADMIN_EMAIL and AURA_ADMIN_PASSWORD in Render Environment.");
        }
        boolean emailMatches = MessageDigest.isEqual(
                adminEmail.trim().toLowerCase().getBytes(StandardCharsets.UTF_8),
                body.email().trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        boolean passwordMatches = MessageDigest.isEqual(
                adminPassword.getBytes(StandardCharsets.UTF_8),
                body.password().getBytes(StandardCharsets.UTF_8));
        if (!emailMatches || !passwordMatches) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email or password is incorrect.");
        }
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(ADMIN_SESSION_KEY, Boolean.TRUE);
        return Map.of("authenticated", true, "email", adminEmail.trim().toLowerCase());
    }

    @GetMapping("/api/admin/me")
    public Map<String, Object> me(HttpServletRequest request) {
        requireAdmin(request);
        return Map.of("authenticated", true, "email", adminEmail.trim().toLowerCase());
    }

    @PostMapping("/api/admin/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        return Map.of("authenticated", false);
    }

    @GetMapping("/api/admin/users")
    public List<UserSummary> listUsers(HttpServletRequest request) {
        requireAdmin(request);
        return users.findAll(org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))
                .stream().map(this::summary).toList();
    }

    private UserSummary summary(AccountUser user) {
        GenerationService.Usage usage = generations.usage(user.getOwnerToken());
        return new UserSummary(user.getId(), user.getEmail(), user.getCreatedAt(),
                usage.plan().name(), usage.imagesLeft(), usage.videosLeft());
    }

    private static void requireAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !Boolean.TRUE.equals(session.getAttribute(ADMIN_SESSION_KEY))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin login required.");
        }
    }
}
