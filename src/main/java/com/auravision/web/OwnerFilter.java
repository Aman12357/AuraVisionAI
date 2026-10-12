package com.auravision.web;

import com.auravision.model.AccountUser;
import com.auravision.service.AccountService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OwnerFilter extends OncePerRequestFilter {
    public static final String COOKIE = "av_token";
    public static final String SESSION_COOKIE = "av_session";
    public static final String ATTR = "owner";
    public static final String ACCOUNT_ATTR = "accountOwner";
    public static final String ACCOUNT_EMAIL_ATTR = "accountEmail";
    private final AccountService accounts;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        return !path.startsWith("/api/") || "/api/payments/razorpay/webhook".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String anonymousToken = cookie(req, COOKIE);
        if (anonymousToken == null || !anonymousToken.matches("[0-9a-f-]{36}")) {
            anonymousToken = UUID.randomUUID().toString();
            res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, anonymousToken)
                .httpOnly(true).secure(isHttps(req)).sameSite("Lax").path("/")
                .maxAge(Duration.ofDays(365)).build().toString());
        }

        String owner = anonymousToken;
        AccountUser account = accounts.accountForSession(cookie(req, SESSION_COOKIE)).orElse(null);
        if (account != null) {
            owner = account.getOwnerToken();
            req.setAttribute(ACCOUNT_ATTR, account.getOwnerToken());
            req.setAttribute(ACCOUNT_EMAIL_ATTR, account.getEmail());
        }
        req.setAttribute(ATTR, owner);

        if (req.getRequestURI().startsWith("/api/generations") && account == null && accounts.isPersistentDatabaseConfigured()) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setCharacterEncoding("UTF-8");
            res.setContentType("application/json");
            res.getWriter().write("{\"message\":\"Create an AuraVision account or sign in to use the Studio.\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    private static String cookie(HttpServletRequest req, String name) {
        if (req.getCookies() != null) {
            for (Cookie cookie : req.getCookies()) if (name.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private static boolean isHttps(HttpServletRequest request) {
        return request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
    }
}
