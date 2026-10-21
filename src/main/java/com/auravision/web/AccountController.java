package com.auravision.web;

import com.auravision.model.AccountUser;
import com.auravision.service.AccountService;
import com.auravision.service.BillingService;
import com.auravision.service.GenerationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AccountController {
    private final AccountService accounts;
    private final BillingService billing;
    private final GenerationService generations;

    public record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
                                  @NotBlank @Pattern(regexp = "[0-9]{6}", message = "Password must be exactly 6 digits.") String password) {}
    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(max = 128) String password) {}
    public record AuthView(boolean authenticated, String email, Instant createdAt, String storageKey,
                           String plan, Instant expiresAt, String subscriptionStatus,
                           int imagesLeft, int videosLeft, boolean databaseReady) {}

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthView register(@Valid @RequestBody RegisterRequest body,
                             @RequestAttribute(OwnerFilter.ATTR) String anonymousOwner,
                             HttpServletRequest request, HttpServletResponse response) {
        if (request.getAttribute(OwnerFilter.ACCOUNT_ATTR) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You are already signed in.");
        }
        AccountUser user = accounts.register(body.email(), body.password(), anonymousOwner);
        writeSessionCookie(request, response, accounts.createSession(user));
        return view(user);
    }

    @PostMapping("/login")
    public AuthView login(@Valid @RequestBody LoginRequest body,
                          HttpServletRequest request, HttpServletResponse response) {
        AccountUser user = accounts.authenticate(body.email(), body.password());
        writeSessionCookie(request, response, accounts.createSession(user));
        return view(user);
    }

    @GetMapping("/me")
    public AuthView me(@RequestAttribute(OwnerFilter.ATTR) String owner, HttpServletRequest request) {
        Object accountOwner = request.getAttribute(OwnerFilter.ACCOUNT_ATTR);
        if (!(accountOwner instanceof String stableOwner)) {
            GenerationService.Usage usage = generations.usage(owner);
            return new AuthView(false, null, null, "visitor_" + owner, usage.plan().name(),
                billing.expiry(owner).orElse(null), billing.subscriptionStatus(owner).orElse(null),
                usage.imagesLeft(), usage.videosLeft(), accounts.isPersistentDatabaseConfigured());
        }
        AccountUser user = accounts.findByOwnerToken(stableOwner).orElse(null);
        return user == null
            ? new AuthView(false, null, null, "visitor_" + owner, "FREE", null, null, 0, 0,
                accounts.isPersistentDatabaseConfigured())
            : view(user);
    }

    @PostMapping("/logout")
    public AuthView logout(@CookieValue(name = OwnerFilter.SESSION_COOKIE, required = false) String rawToken,
                           HttpServletRequest request, HttpServletResponse response) {
        accounts.deleteSession(rawToken);
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(OwnerFilter.SESSION_COOKIE, "")
            .httpOnly(true).secure(isHttps(request)).sameSite("Lax").path("/")
            .maxAge(Duration.ZERO).build().toString());
        String newAnonymousOwner = UUID.randomUUID().toString();
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(OwnerFilter.COOKIE, newAnonymousOwner)
            .httpOnly(true).secure(isHttps(request)).sameSite("Lax").path("/")
            .maxAge(Duration.ofDays(365)).build().toString());
        return new AuthView(false, null, null, "visitor_" + newAnonymousOwner, "FREE", null, null, 0, 0,
            accounts.isPersistentDatabaseConfigured());
    }

    private AuthView view(AccountUser user) {
        GenerationService.Usage usage = generations.usage(user.getOwnerToken());
        return new AuthView(true, user.getEmail(), user.getCreatedAt(), "user_" + user.getId(),
            usage.plan().name(), billing.expiry(user.getOwnerToken()).orElse(null),
            billing.subscriptionStatus(user.getOwnerToken()).orElse(null),
            usage.imagesLeft(), usage.videosLeft(), accounts.isPersistentDatabaseConfigured());
    }

    private void writeSessionCookie(HttpServletRequest request, HttpServletResponse response, String rawToken) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(OwnerFilter.SESSION_COOKIE, rawToken)
            .httpOnly(true).secure(isHttps(request)).sameSite("Lax").path("/")
            .maxAge(Duration.ofDays(30)).build().toString());
    }

    private static boolean isHttps(HttpServletRequest request) {
        return request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
    }
}
