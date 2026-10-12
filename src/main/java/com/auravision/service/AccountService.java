package com.auravision.service;

import com.auravision.model.AccountSession;
import com.auravision.model.AccountUser;
import com.auravision.repo.AccountSessionRepository;
import com.auravision.repo.AccountUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AccountService {
    private static final Duration SESSION_LIFETIME = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AccountUserRepository users;
    private final AccountSessionRepository sessions;
    private final PasswordHasher passwordHasher;
    @Value("$" + "{spring.datasource.url:}") private String datasourceUrl;

    @Transactional
    public AccountUser register(String email, String password, String anonymousOwnerToken) {
        if (!isPersistentDatabaseConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Account registration requires persistent PostgreSQL. The owner must configure SPRING_PROFILES_ACTIVE=postgres and DB_URL, DB_USER, DB_PASSWORD in Render first.");
        }
        String normalisedEmail = normaliseEmail(email);
        if (users.findByEmail(normalisedEmail).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account already exists for this email. Sign in instead.");
        }
        if (anonymousOwnerToken == null || !anonymousOwnerToken.matches("[0-9a-f-]{36}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Your browser session could not be verified. Refresh and try again.");
        }
        AccountUser user = new AccountUser();
        user.setEmail(normalisedEmail);
        user.setPasswordHash(passwordHasher.hash(password));
        // Keep creations made in this browser associated with the new account.
        user.setOwnerToken(anonymousOwnerToken);
        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account already exists for this email or browser. Sign in instead.");
        }
    }

    @Transactional(readOnly = true)
    public AccountUser authenticate(String email, String password) {
        if (!isPersistentDatabaseConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Account sign-in requires the persistent PostgreSQL database. Check the AuraVision service setup in Render.");
        }
        AccountUser user = users.findByEmail(normaliseEmail(email))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email or password is incorrect."));
        if (!passwordHasher.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email or password is incorrect.");
        }
        return user;
    }

    @Transactional
    public String createSession(AccountUser user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        AccountSession session = new AccountSession();
        session.setUser(user);
        session.setTokenHash(hashToken(rawToken));
        session.setExpiresAt(Instant.now().plus(SESSION_LIFETIME));
        sessions.save(session);
        return rawToken;
    }

    @Transactional(readOnly = true)
    public Optional<AccountUser> accountForSession(String rawToken) {
        if (rawToken == null || !rawToken.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        return sessions.findByTokenHashAndExpiresAtAfter(hashToken(rawToken), Instant.now()).map(AccountSession::getUser);
    }

    @Transactional
    public void deleteSession(String rawToken) {
        if (rawToken != null && rawToken.matches("[A-Za-z0-9_-]{43}")) sessions.deleteByTokenHash(hashToken(rawToken));
    }

    @Transactional(readOnly = true)
    public Optional<AccountUser> findByOwnerToken(String ownerToken) {
        return users.findByOwnerToken(ownerToken);
    }

    public boolean isPersistentDatabaseConfigured() {
        return datasourceUrl != null && datasourceUrl.startsWith("jdbc:postgresql:");
    }

    private static String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String hashToken(String rawToken) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Session hashing is unavailable.", e);
        }
    }
}
