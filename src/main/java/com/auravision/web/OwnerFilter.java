package com.auravision.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/**
 * Gives every browser an anonymous, HttpOnly id cookie. It keeps galleries private and ties a purchase
 * to the browser that made it. Replace with real login (Spring Security) before going public.
 */
@Component
public class OwnerFilter extends OncePerRequestFilter {
    public static final String COOKIE = "av_token";
    public static final String ATTR = "owner";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) { return !req.getRequestURI().startsWith("/api/"); }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String token = null;
        if (req.getCookies() != null)
            for (Cookie c : req.getCookies()) if (COOKIE.equals(c.getName())) token = c.getValue();
        if (token == null || !token.matches("[0-9a-f\\-]{36}")) {
            token = UUID.randomUUID().toString();
            res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, token)
                    .httpOnly(true).secure(req.isSecure()).sameSite("Lax").path("/")
                    .maxAge(Duration.ofDays(365)).build().toString());
        }
        req.setAttribute(ATTR, token);
        chain.doFilter(req, res);
    }
}
