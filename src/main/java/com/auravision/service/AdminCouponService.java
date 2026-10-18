package com.auravision.service;

import com.auravision.model.AdminCoupon;
import com.auravision.repo.AdminCouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AdminCouponService {
    private final AdminCouponRepository coupons;

    public record CouponView(Long id, String code, int discountPercent, boolean active,
                             Integer maxRedemptions, int redeemedCount, Instant expiresAt, Instant createdAt) {}
    public record CouponQuote(String code, int discountPercent, int originalAmountRupees,
                              int discountedAmountRupees, boolean valid, String message) {}

    @Transactional(readOnly = true)
    public List<CouponView> list() {
        return coupons.findAll().stream()
            .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
            .map(this::view).toList();
    }

    @Transactional
    public CouponView create(String rawCode, int percent, Integer maxRedemptions, Instant expiresAt) {
        String code = normalise(rawCode);
        if (!code.matches("[A-Z0-9_-]{3,40}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Coupon code must be 3–40 letters, numbers, hyphens or underscores.");
        if (percent < 1 || percent > 90)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Discount must be between 1% and 90%.");
        if (maxRedemptions != null && maxRedemptions < 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Maximum redemptions must be at least 1 or left blank.");
        if (expiresAt != null && !expiresAt.isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expiry must be in the future.");
        if (coupons.findByCodeIgnoreCase(code).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That coupon code already exists.");
        AdminCoupon coupon = new AdminCoupon();
        coupon.setCode(code);
        coupon.setDiscountPercent(percent);
        coupon.setMaxRedemptions(maxRedemptions);
        coupon.setExpiresAt(expiresAt);
        coupon.setActive(true);
        return view(coupons.save(coupon));
    }

    @Transactional
    public CouponView setActive(Long id, boolean active) {
        AdminCoupon coupon = coupons.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Coupon not found."));
        coupon.setActive(active);
        return view(coupons.save(coupon));
    }

    @Transactional(readOnly = true)
    public CouponQuote quote(String rawCode, int amountRupees) {
        if (amountRupees < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid checkout amount is required.");
        String code = normalise(rawCode);
        AdminCoupon coupon = coupons.findByCodeIgnoreCase(code)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Coupon code is invalid."));
        validateUsable(coupon);
        int discount = (int) Math.round(amountRupees * (coupon.getDiscountPercent() / 100.0));
        int finalAmount = Math.max(1, amountRupees - discount);
        return new CouponQuote(coupon.getCode(), coupon.getDiscountPercent(), amountRupees,
            finalAmount, true, "Coupon applied.");
    }

    @Transactional
    public void recordRedemption(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) return;
        AdminCoupon coupon = coupons.findByCodeIgnoreCase(normalise(rawCode)).orElse(null);
        if (coupon == null) return;
        validateUsable(coupon);
        coupon.setRedeemedCount(coupon.getRedeemedCount() + 1);
        coupons.save(coupon);
    }

    private static void validateUsable(AdminCoupon coupon) {
        if (!coupon.isActive()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This coupon is inactive.");
        if (coupon.getExpiresAt() != null && !coupon.getExpiresAt().isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This coupon has expired.");
        if (coupon.getMaxRedemptions() != null && coupon.getRedeemedCount() >= coupon.getMaxRedemptions())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This coupon has reached its redemption limit.");
    }

    private CouponView view(AdminCoupon c) {
        return new CouponView(c.getId(), c.getCode(), c.getDiscountPercent(), c.isActive(),
            c.getMaxRedemptions(), c.getRedeemedCount(), c.getExpiresAt(), c.getCreatedAt());
    }

    private static String normalise(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }
}
