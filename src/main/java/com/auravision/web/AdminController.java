package com.auravision.web;

import com.auravision.model.AccountUser;
import com.auravision.model.Plan;
import com.auravision.model.PlanPricing;
import com.auravision.repo.AccountSessionRepository;
import com.auravision.repo.AccountUserRepository;
import com.auravision.service.AdminCouponService;
import com.auravision.service.GenerationService;
import com.auravision.service.PasswordHasher;
import com.auravision.service.PlanPricingService;
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
import java.util.Locale;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AdminController {
    private static final String ADMIN_SESSION_KEY = "AURA_ADMIN_AUTHENTICATED";

    private final AccountUserRepository users;
    private final AccountSessionRepository sessions;
    private final GenerationService generations;
    private final PasswordHasher passwordHasher;
    private final AdminCouponService coupons;
    private final PlanPricingService pricing;

    @Value("${AURA_ADMIN_EMAIL:}") private String adminEmail;
    @Value("${AURA_ADMIN_PASSWORD:}") private String adminPassword;

    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(max = 128) String password) {}
    public record UserSummary(Long id, String email, Instant createdAt, String plan,
                              int imagesLeftToday, int videosLeftToday,
                              int bonusImageCredits, int bonusVideoCredits) {}
    public record CreditAdjustment(int imageDelta, int videoDelta) {}
    public record PasswordReset(@NotBlank @Size(min = 10, max = 128) String newPassword) {}
    public record CouponCreate(@NotBlank @Size(min = 3, max = 40) String code,
                               int discountPercent, Integer maxRedemptions, Instant expiresAt) {}
    public record CouponActive(boolean active) {}
    public record PricingUpdate(int monthlyRupees, int yearlyRupees) {}
    public record PricingView(String plan, String name, int monthlyRupees, int yearlyRupees,
                              int imagesPerDay, int videosPerDay) {}

    @GetMapping("/admin/login")
    public ResponseEntity<Void> loginPage() {
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", "/admin/login.html").build();
    }

    @GetMapping("/admin")
    public ResponseEntity<Void> adminHome() {
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", "/admin/dashboard.html").build();
    }

    @PostMapping("/api/admin/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Admin login is not configured. Set AURA_ADMIN_EMAIL and AURA_ADMIN_PASSWORD in Render Environment.");
        }
        boolean emailMatches = MessageDigest.isEqual(
                adminEmail.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                body.email().trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        boolean passwordMatches = MessageDigest.isEqual(
                adminPassword.getBytes(StandardCharsets.UTF_8), body.password().getBytes(StandardCharsets.UTF_8));
        if (!emailMatches || !passwordMatches)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email or password is incorrect.");
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(ADMIN_SESSION_KEY, Boolean.TRUE);
        return Map.of("authenticated", true, "email", adminEmail.trim().toLowerCase(Locale.ROOT));
    }

    @GetMapping("/api/admin/me")
    public Map<String, Object> me(HttpServletRequest request) {
        requireAdmin(request);
        return Map.of("authenticated", true, "email", adminEmail.trim().toLowerCase(Locale.ROOT));
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

    @PostMapping("/api/admin/users/{id}/credits")
    public UserSummary adjustCredits(HttpServletRequest request, @PathVariable Long id,
                                     @Valid @RequestBody CreditAdjustment adjustment) {
        requireAdmin(request);
        if (Math.abs((long) adjustment.imageDelta()) > 10000 || Math.abs((long) adjustment.videoDelta()) > 10000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each adjustment must be between -10,000 and 10,000.");
        AccountUser user = findUser(id);
        long images = (long) user.getBonusImageCredits() + adjustment.imageDelta();
        long videos = (long) user.getBonusVideoCredits() + adjustment.videoDelta();
        if (images < 0 || videos < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Credit balances cannot become negative.");
        user.setBonusImageCredits((int) images);
        user.setBonusVideoCredits((int) videos);
        users.save(user);
        return summary(user);
    }

    @PostMapping("/api/admin/users/{id}/password")
    public Map<String, Object> resetPassword(HttpServletRequest request, @PathVariable Long id,
                                             @Valid @RequestBody PasswordReset body) {
        requireAdmin(request);
        AccountUser user = findUser(id);
        user.setPasswordHash(passwordHasher.hash(body.newPassword()));
        users.save(user);
        sessions.deleteByUser_Id(user.getId());
        return Map.of("success", true, "message", "Password updated. Existing user sessions have been signed out.");
    }

    @GetMapping("/api/admin/pricing")
    public List<PricingView> listPricing(HttpServletRequest request) {
        requireAdmin(request);
        return pricing.listPaid().stream().map(this::pricingView).toList();
    }

    @PutMapping("/api/admin/pricing/{plan}")
    public PricingView updatePricing(HttpServletRequest request, @PathVariable String plan,
                                     @Valid @RequestBody PricingUpdate body) {
        requireAdmin(request);
        final Plan selected;
        try { selected = Plan.valueOf(plan.trim().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown subscription plan.");
        }
        PlanPricing saved = pricing.update(selected, body.monthlyRupees(), body.yearlyRupees());
        return pricingView(saved);
    }

    private PricingView pricingView(PlanPricing row) {
        Plan plan = row.getPlan();
        return new PricingView(plan.name(), plan.name().substring(0, 1) + plan.name().substring(1).toLowerCase(Locale.ROOT),
            row.getMonthlyRupees(), row.getYearlyRupees(), plan.imagesPerDay, plan.videosPerDay);
    }

    @GetMapping("/api/admin/coupons")
    public List<AdminCouponService.CouponView> listCoupons(HttpServletRequest request) {
        requireAdmin(request);
        return coupons.list();
    }

    @PostMapping("/api/admin/coupons")
    public AdminCouponService.CouponView createCoupon(HttpServletRequest request,
                                                       @Valid @RequestBody CouponCreate body) {
        requireAdmin(request);
        return coupons.create(body.code(), body.discountPercent(), body.maxRedemptions(), body.expiresAt());
    }

    @PostMapping("/api/admin/coupons/{id}/active")
    public AdminCouponService.CouponView setCouponActive(HttpServletRequest request, @PathVariable Long id,
                                                         @Valid @RequestBody CouponActive body) {
        requireAdmin(request);
        return coupons.setActive(id, body.active());
    }

    private AccountUser findUser(Long id) {
        return users.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User account not found."));
    }

    private UserSummary summary(AccountUser user) {
        GenerationService.Usage usage = generations.usage(user.getOwnerToken());
        return new UserSummary(user.getId(), user.getEmail(), user.getCreatedAt(), usage.plan().name(),
            usage.imagesLeft(), usage.videosLeft(), user.getBonusImageCredits(), user.getBonusVideoCredits());
    }

    private static void requireAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !Boolean.TRUE.equals(session.getAttribute(ADMIN_SESSION_KEY)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin login required.");
    }
}
