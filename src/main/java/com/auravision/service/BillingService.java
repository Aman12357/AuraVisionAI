package com.auravision.service;

import com.auravision.model.Payment;
import com.auravision.model.Plan;
import com.auravision.model.Subscription;
import com.auravision.repo.PaymentRepository;
import com.auravision.repo.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BillingService {
    private static final Set<String> METHODS = Set.of("card", "upi", "bank", "wallet");
    private final PaymentRepository payments;
    private final SubscriptionRepository subs;

    public Plan planFor(String owner) {
        return subs.findByOwnerToken(owner).filter(s -> s.getExpiresAt().isAfter(Instant.now()))
                .map(Subscription::getPlan).orElse(Plan.FREE);
    }

    public Optional<Instant> expiry(String owner) {
        return subs.findByOwnerToken(owner).map(Subscription::getExpiresAt).filter(e -> e.isAfter(Instant.now()));
    }

    /** Step 1: create an order. The price is computed here, never trusted from the browser. */
    @Transactional
    public Payment createOrder(String owner, Plan plan, boolean yearly, String email) {
        if (plan == Plan.FREE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a paid plan.");
        Payment p = new Payment();
        p.setOrderId("AV" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        p.setOwnerToken(owner);
        p.setPlan(plan);
        p.setYearly(yearly);
        p.setAmountRupees(plan.price(yearly));
        p.setEmail(email.trim());
        return payments.save(p);
    }

    /**
     * Step 2: confirm payment and activate the plan.
     * DEMO: this marks the order paid without charging anyone.
     * PRODUCTION: before marking PAID, verify the gateway's payment signature server-side
     * (for Razorpay: HMAC-SHA256 of order_id|payment_id with your key secret).
     */
    @Transactional
    public Payment pay(String owner, String orderId, String method) {
        Payment p = payments.findByOrderIdAndOwnerToken(orderId, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found."));
        if (p.getStatus() == Payment.Status.PAID) return p;   // safe to call twice
        if (!METHODS.contains(method)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported payment method.");

        p.setMethod(method);
        p.setTxnRef("TXN" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase());
        p.setStatus(Payment.Status.PAID);
        p.setPaidAt(Instant.now());

        Subscription s = subs.findByOwnerToken(owner).orElseGet(Subscription::new);
        s.setOwnerToken(owner);
        s.setPlan(p.getPlan());
        s.setEmail(p.getEmail());
        s.setExpiresAt(Instant.now().plus(Duration.ofDays(p.isYearly() ? 365 : 30)));
        subs.save(s);
        return payments.save(p);
    }
}
