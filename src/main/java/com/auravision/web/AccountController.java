package com.auravision.web;

import com.auravision.model.AccountUser;
import com.auravision.service.AccountService;
import com.auravision.service.BillingService;
import com.auravision.service.GenerationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import java.util.Map;
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
    @Value("${GOOGLE_CLIENT_ID:}") private String googleClientId;
    private final RestClient googleRestClient = RestClient.create();

    public record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
                                  @NotBlank @Pattern(regexp = "[0-9]{6}", message = "Password must be exactly 6 digits.") String password) {}
    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(max = 128) String password) {}
    public record GoogleLoginRequest(@NotBlank String credential) {}
    public record GoogleConfig(boolean enabled, String clientId) {}
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

    @GetMapping("/google/config")
    public GoogleConfig googleConfig() {
        return new GoogleConfig(googleClientId != null && !googleClientId.isBlank(), googleClientId);
    }

    @PostMapping("/google")
    public AuthView googleLogin(@Valid @RequestBody GoogleLoginRequest body,
                                @RequestAttribute(OwnerFilter.ATTR) String anonymousOwner,
                                HttpServletRequest request, HttpServletResponse response) {
        if (googleClientId == null || googleClientId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Google sign-in is not configured yet. The site owner must set GOOGLE_CLIENT_ID in Render.");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = googleRestClient.get()
                .uri("https://oauth2.googleapis.com/tokeninfo?id_token={token}", body.credential())
                .retrieve().body(Map.class);
            if (claims == null
                    || !googleClientId.equals(String.valueOf(claims.get("aud")))
                    || !"true".equalsIgnoreCase(String.valueOf(claims.get("email_verified")))
                    || !("accounts.google.com".equals(String.valueOf(claims.get("iss")))
                         || "https://accounts.google.com".equals(String.valueOf(claims.get("iss"))))
                    || claims.get("email") == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google could not verify this account. Please try again.");
            }
            AccountUser user = accounts.findOrCreateGoogleAccount(String.valueOf(claims.get("email")), anonymousOwner);
            writeSessionCookie(request, response, accounts.createSession(user));
            return view(user);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google sign-in verification failed. Please try again.");
        }
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
