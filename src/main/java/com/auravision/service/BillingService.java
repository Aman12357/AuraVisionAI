package com.auravision.service;

import com.auravision.model.Payment;
import com.auravision.model.Plan;
import com.auravision.model.RazorpayPlanMapping;
import com.auravision.model.Subscription;
import com.auravision.repo.PaymentRepository;
import com.auravision.repo.RazorpayPlanRepository;
import com.auravision.repo.SubscriptionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BillingService {
    private final PaymentRepository payments;
    private final SubscriptionRepository subs;
    private final RazorpayPlanRepository razorpayPlans;
    private final PlanPricingService pricing;
    private final RazorpayGateway razorpay;
    @Value("$" + "{spring.datasource.url:}") private String datasourceUrl;

    public record CheckoutStart(String orderId, String subscriptionId, String keyId, String email,
                                String plan, boolean yearly, int amountRupees, String description) {}
    public record OneTimeCheckoutStart(String orderId, String razorpayOrderId, String keyId, String email,
                                       String plan, boolean yearly, int amountRupees, String description) {}
    public record PurchaseStatus(String orderId, String status, String plan, boolean yearly,
                                 int amountRupees, Instant paidAt, Instant expiresAt) {}
    public record HistoryItem(String orderId, String plan, boolean yearly, int amountRupees,
                              String status, String method, String transactionReference,
                              Instant createdAt, Instant paidAt) {}

    public boolean isPersistentDatabaseConfigured() {
        return datasourceUrl != null && datasourceUrl.startsWith("jdbc:postgresql:");
    }
    public boolean paymentSetupReady() {
        return isPersistentDatabaseConfigured() && razorpay.isConfigured();
    }
    public String paymentSetupMessage() {
        if (!isPersistentDatabaseConfigured()) {
            return "Live checkout is disabled until persistent PostgreSQL is connected. On Render, create a PostgreSQL database, set SPRING_PROFILES_ACTIVE=postgres, then set DB_URL, DB_USER and DB_PASSWORD from its connection details.";
        }
        if (!razorpay.isConfigured()) {
            return "Live checkout is disabled until RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET and RAZORPAY_WEBHOOK_SECRET are set in Render Environment.";
        }
        return "Razorpay one-time checkout is configured. Test a single payment in Razorpay Test Mode before enabling live charges.";
    }

    public Plan planFor(String owner) {
        return subs.findByOwnerToken(owner)
            .filter(s -> s.getExpiresAt() != null && s.getExpiresAt().isAfter(Instant.now()))
            .filter(s -> s.getStatus() == null || List.of("ACTIVE", "CANCEL_AT_CYCLE_END", "PENDING", "HALTED", "COMPLETED").contains(s.getStatus()))
            .map(Subscription::getPlan).orElse(Plan.FREE);
    }
    public Optional<Instant> expiry(String owner) {
        return subs.findByOwnerToken(owner).map(Subscription::getExpiresAt)
            .filter(e -> e != null && e.isAfter(Instant.now()));
    }
    public Optional<String> subscriptionStatus(String owner) {
        return subs.findByOwnerToken(owner).map(Subscription::getStatus);
    }

    /** Creates a single-charge order. Access is granted only after a verified captured payment. */
    @Transactional
    public OneTimeCheckoutStart createOneTimeOrder(String owner, String email, Plan plan, boolean yearly) {
        if (!paymentSetupReady()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, paymentSetupMessage());
        if (plan == null || plan == Plan.FREE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a paid plan.");
        if (planFor(owner) != Plan.FREE) throw new ResponseStatusException(HttpStatus.CONFLICT,
            "You already have active paid access. Wait until it expires before purchasing another plan.");
        int amount = pricing.price(plan, yearly);
        Payment purchase = new Payment();
        purchase.setOrderId("AV" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        purchase.setOwnerToken(owner);
        purchase.setPlan(plan);
        purchase.setYearly(yearly);
        purchase.setAmountRupees(amount);
        purchase.setEmail(email);
        purchase.setStatus(Payment.Status.CREATED);
        purchase = payments.saveAndFlush(purchase);
        try {
            String razorpayOrderId = razorpay.createOrder(amount, purchase.getId().toString(), plan, yearly, email);
            // Legacy column stores the provider reference for both old subscriptions and one-time orders.
            purchase.setRazorpaySubscriptionId(razorpayOrderId);
            purchase.setStatus(Payment.Status.PENDING);
            payments.save(purchase);
            return new OneTimeCheckoutStart(purchase.getOrderId(), razorpayOrderId, razorpay.publicKeyId(), email,
                plan.name(), yearly, amount, title(plan.name()) + (yearly ? " 365-day access (one-time)" : " 30-day access (one-time)"));
        } catch (RuntimeException e) {
            purchase.setStatus(Payment.Status.FAILED);
            payments.save(purchase);
            throw e;
        }
    }

    /** Verifies a one-time order signature; activation waits for the captured-payment webhook. */
    @Transactional
    public PurchaseStatus verifyOneTimeCheckout(String owner, String orderId, String razorpayOrderId,
                                                String paymentId, String signature) {
        Payment purchase = payments.findByOrderIdAndOwnerToken(orderId, owner)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase not found."));
        if (purchase.getRazorpaySubscriptionId() == null || !purchase.getRazorpaySubscriptionId().equals(razorpayOrderId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Razorpay order does not match this purchase.");
        if (!razorpay.verifyOrderSignature(razorpayOrderId, paymentId, signature))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Razorpay payment signature could not be verified.");
        purchase.setCheckoutPaymentId(paymentId);
        if (purchase.getStatus() != Payment.Status.PAID) purchase.setStatus(Payment.Status.PENDING);
        payments.save(purchase);
        return purchaseStatus(owner, orderId);
    }

    /** Creates a real recurring subscription. Access is not activated by creating checkout. */
    @Transactional
    public CheckoutStart createSubscription(String owner, String email, Plan plan, boolean yearly) {
        if (!paymentSetupReady()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, paymentSetupMessage());
        }
        if (plan == null || plan == Plan.FREE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a paid plan.");
        }
        if (planFor(owner) != Plan.FREE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "You already have a paid plan. Manage or cancel it from My Account before starting another subscription.");
        }

        RazorpayPlanMapping mapping = getOrCreatePlan(plan, yearly);
        Payment purchase = new Payment();
        purchase.setOrderId("AV" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        purchase.setOwnerToken(owner);
        purchase.setPlan(plan);
        purchase.setYearly(yearly);
        purchase.setAmountRupees(pricing.price(plan, yearly));
        purchase.setEmail(email);
        purchase.setRazorpayPlanId(mapping.getRazorpayPlanId());
        purchase.setStatus(Payment.Status.CREATED);
        purchase = payments.saveAndFlush(purchase);

        try {
            String subscriptionId = razorpay.createSubscription(mapping.getRazorpayPlanId(),
                purchase.getId().toString(), plan, yearly, email);
            purchase.setRazorpaySubscriptionId(subscriptionId);
            purchase.setStatus(Payment.Status.PENDING);
            payments.save(purchase);
            return new CheckoutStart(purchase.getOrderId(), subscriptionId, razorpay.publicKeyId(), email,
                plan.name(), yearly, purchase.getAmountRupees(),
                title(plan.name()) + (yearly ? " yearly" : " monthly") + " subscription");
        } catch (RuntimeException e) {
            purchase.setStatus(Payment.Status.FAILED);
            payments.save(purchase);
            throw e;
        }
    }

    private RazorpayPlanMapping getOrCreatePlan(Plan plan, boolean yearly) {
        int currentPrice = pricing.price(plan, yearly);
        Optional<RazorpayPlanMapping> existing = razorpayPlans.findByPlanAndYearly(plan, yearly);
        if (existing.isPresent() && existing.get().getAmountRupees() == currentPrice) return existing.get();
        RazorpayPlanMapping mapping = new RazorpayPlanMapping();
        mapping.setPlan(plan);
        mapping.setYearly(yearly);
        mapping.setRazorpayPlanId(razorpay.createPlan(plan, yearly, currentPrice));
        mapping.setAmountRupees(currentPrice);
        if (existing.isPresent()) mapping.setId(existing.get().getId());
        return razorpayPlans.saveAndFlush(mapping);
    }

    /** Confirms the checkout signature, but leaves activation to the verified signed webhook. */
    @Transactional
    public PurchaseStatus verifyCheckout(String owner, String orderId, String paymentId,
                                         String subscriptionId, String signature) {
        Payment purchase = payments.findByOrderIdAndOwnerToken(orderId, owner)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase not found."));
        if (purchase.getRazorpaySubscriptionId() == null || !purchase.getRazorpaySubscriptionId().equals(subscriptionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subscription does not match this purchase.");
        }
        if (!razorpay.verifySubscriptionSignature(paymentId, subscriptionId, signature)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Razorpay payment signature could not be verified.");
        }
        purchase.setCheckoutPaymentId(paymentId);
        if (purchase.getStatus() != Payment.Status.PAID) purchase.setStatus(Payment.Status.PENDING);
        payments.save(purchase);
        return purchaseStatus(owner, orderId);
    }

    @Transactional(readOnly = true)
    public PurchaseStatus purchaseStatus(String owner, String orderId) {
        Payment purchase = payments.findByOrderIdAndOwnerToken(orderId, owner)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase not found."));
        return new PurchaseStatus(purchase.getOrderId(), purchase.getStatus().name(), purchase.getPlan().name(),
            purchase.isYearly(), purchase.getAmountRupees(), purchase.getPaidAt(), expiry(owner).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<HistoryItem> history(String owner) {
        return payments.findTop20ByOwnerTokenOrderByCreatedAtDesc(owner).stream()
            .map(p -> new HistoryItem(p.getOrderId(), p.getPlan().name(), p.isYearly(), p.getAmountRupees(),
                p.getStatus().name(), p.getMethod(), p.getTxnRef(), p.getCreatedAt(), p.getPaidAt()))
            .toList();
    }

    @Transactional
    public Instant cancelAtCycleEnd(String owner) {
        Subscription subscription = subs.findByOwnerToken(owner)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No subscription was found for this account."));
        if (subscription.getRazorpaySubscriptionId() == null || subscription.getRazorpaySubscriptionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This account has no Razorpay subscription to cancel.");
        }
        if ("CANCEL_AT_CYCLE_END".equals(subscription.getStatus())) return subscription.getExpiresAt();
        if (!"ACTIVE".equals(subscription.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an active subscription can be cancelled here.");
        }
        razorpay.cancelSubscription(subscription.getRazorpaySubscriptionId(), true);
        subscription.setStatus("CANCEL_AT_CYCLE_END");
        subs.save(subscription);
        return subscription.getExpiresAt();
    }

    /** Processes events only after webhook signature verification. Duplicate event delivery is safe. */
    @Transactional
    public void handleWebhook(JsonNode root) {
        String event = root.path("event").asText("");
        JsonNode subscriptionEntity = root.path("payload").path("subscription").path("entity");
        JsonNode paymentEntity = root.path("payload").path("payment").path("entity");
        String subscriptionId = subscriptionEntity.path("id").asText("");
        if (subscriptionId.isBlank()) subscriptionId = paymentEntity.path("subscription_id").asText("");
        if (subscriptionId.isBlank() && "payment.captured".equals(event)) {
            String razorpayOrderId = paymentEntity.path("order_id").asText("");
            Payment oneTime = payments.findFirstByRazorpaySubscriptionIdOrderByCreatedAtAsc(razorpayOrderId).orElse(null);
            if (oneTime != null) {
                String paymentId = paymentEntity.path("id").asText("");
                long amount = paymentEntity.path("amount").asLong(-1L);
                String paymentStatus = paymentEntity.path("status").asText("");
                if (!paymentId.isBlank() && amount == (long) oneTime.getAmountRupees() * 100L
                    && "captured".equalsIgnoreCase(paymentStatus)) {
                    activateOneTimePurchase(oneTime, paymentEntity);
                }
            }
            return;
        }
        if (subscriptionId.isBlank()) return;

        Payment original = payments.findFirstByRazorpaySubscriptionIdOrderByCreatedAtAsc(subscriptionId).orElse(null);
        if (original == null) return;

        switch (event) {
            case "subscription.charged", "payment.captured" -> {
                String paymentId = paymentEntity.path("id").asText("");
                long amount = paymentEntity.path("amount").asLong(-1L);
                String paymentStatus = paymentEntity.path("status").asText("");
                if (!paymentId.isBlank() && amount == (long) original.getAmountRupees() * 100L
                    && (paymentStatus.isBlank() || "captured".equalsIgnoreCase(paymentStatus))) {
                    activateFromCharge(original, subscriptionEntity, paymentEntity, subscriptionId);
                }
            }
            case "subscription.activated" -> {
                // An activated event without a captured matching charge does not unlock paid features.
                String paymentId = paymentEntity.path("id").asText("");
                long amount = paymentEntity.path("amount").asLong(-1L);
                String paymentStatus = paymentEntity.path("status").asText("");
                if (!paymentId.isBlank() && amount == (long) original.getAmountRupees() * 100L
                    && "captured".equalsIgnoreCase(paymentStatus)) {
                    activateFromCharge(original, subscriptionEntity, paymentEntity, subscriptionId);
                } else {
                    markPending(original, subscriptionId);
                }
            }
            case "subscription.pending" -> updateProviderStatus(original, "PENDING", false);
            case "subscription.halted" -> updateProviderStatus(original, "HALTED", false);
            case "subscription.cancelled" -> updateCancellation(original, subscriptionEntity);
            case "subscription.completed" -> updateProviderStatus(original, "COMPLETED", false);
            default -> {
                // Unneeded event types are acknowledged; Razorpay can deliver additional events over time.
            }
        }
    }

    private void activateOneTimePurchase(Payment purchase, JsonNode providerPayment) {
        String paymentId = providerPayment.path("id").asText("");
        if (paymentId.isBlank() || payments.findByGatewayPaymentId(paymentId).isPresent()) return;
        Instant now = Instant.now();
        Subscription local = subs.findByOwnerToken(purchase.getOwnerToken()).orElseGet(Subscription::new);
        local.setOwnerToken(purchase.getOwnerToken());
        local.setPlan(purchase.getPlan());
        local.setEmail(purchase.getEmail());
        local.setRazorpaySubscriptionId(null);
        local.setStatus("ACTIVE");
        local.setExpiresAt(now.plus(purchase.isYearly() ? Duration.ofDays(365) : Duration.ofDays(30)));
        subs.save(local);
        purchase.setGatewayPaymentId(paymentId);
        purchase.setTxnRef(paymentId);
        purchase.setMethod(providerPayment.path("method").asText("razorpay"));
        purchase.setStatus(Payment.Status.PAID);
        purchase.setPaidAt(now);
        payments.save(purchase);
    }

    private void markPending(Payment purchase, String subscriptionId) {
        Subscription local = subs.findByOwnerToken(purchase.getOwnerToken()).orElseGet(Subscription::new);
        local.setOwnerToken(purchase.getOwnerToken());
        local.setPlan(purchase.getPlan());
        local.setEmail(purchase.getEmail());
        local.setRazorpaySubscriptionId(subscriptionId);
        if (local.getExpiresAt() == null || !local.getExpiresAt().isAfter(Instant.now())) {
            local.setExpiresAt(null);
            local.setStatus("PENDING_PAYMENT");
        } else if (!"CANCEL_AT_CYCLE_END".equals(local.getStatus())) {
            local.setStatus("PENDING");
        }
        subs.save(local);
        if (purchase.getStatus() != Payment.Status.PAID) {
            purchase.setStatus(Payment.Status.PENDING);
            payments.save(purchase);
        }
    }

    private void activateFromCharge(Payment original, JsonNode providerSubscription,
                                    JsonNode providerPayment, String subscriptionId) {
        Instant now = Instant.now();
        Subscription local = subs.findByOwnerToken(original.getOwnerToken()).orElseGet(Subscription::new);
        String previousStatus = local.getStatus();
        if ("CANCELLED".equals(previousStatus)) return;
        local.setOwnerToken(original.getOwnerToken());
        local.setPlan(original.getPlan());
        local.setEmail(original.getEmail());
        local.setRazorpaySubscriptionId(subscriptionId);
        local.setStatus("CANCEL_AT_CYCLE_END".equals(previousStatus) ? "CANCEL_AT_CYCLE_END" : "ACTIVE");
        Instant providerEnd = epoch(providerSubscription.path("current_end").asLong(0L));
        if (providerEnd != null && providerEnd.isAfter(now)) {
            if (local.getExpiresAt() == null || providerEnd.isAfter(local.getExpiresAt())) local.setExpiresAt(providerEnd);
        } else if (local.getExpiresAt() == null || !local.getExpiresAt().isAfter(now)) {
            local.setExpiresAt(now.plus(original.isYearly() ? Duration.ofDays(365) : Duration.ofDays(30)));
        }
        subs.save(local);

        String gatewayPaymentId = providerPayment.path("id").asText("");
        if (gatewayPaymentId.isBlank() || payments.findByGatewayPaymentId(gatewayPaymentId).isPresent()) return;
        String method = providerPayment.path("method").asText("razorpay");
        if (original.getGatewayPaymentId() == null || original.getGatewayPaymentId().isBlank()) {
            original.setGatewayPaymentId(gatewayPaymentId);
            original.setTxnRef(gatewayPaymentId);
            original.setMethod(method);
            original.setStatus(Payment.Status.PAID);
            original.setPaidAt(now);
            payments.save(original);
        } else if (!original.getGatewayPaymentId().equals(gatewayPaymentId)) {
            Payment renewal = new Payment();
            renewal.setOrderId("AV" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
            renewal.setOwnerToken(original.getOwnerToken());
            renewal.setPlan(original.getPlan());
            renewal.setYearly(original.isYearly());
            renewal.setAmountRupees(original.getAmountRupees());
            renewal.setEmail(original.getEmail());
            renewal.setRazorpayPlanId(original.getRazorpayPlanId());
            renewal.setRazorpaySubscriptionId(subscriptionId);
            renewal.setGatewayPaymentId(gatewayPaymentId);
            renewal.setTxnRef(gatewayPaymentId);
            renewal.setMethod(method);
            renewal.setStatus(Payment.Status.PAID);
            renewal.setPaidAt(now);
            payments.save(renewal);
        }
    }

    private void updateProviderStatus(Payment original, String status, boolean stopImmediately) {
        Subscription local = subs.findByOwnerToken(original.getOwnerToken()).orElseGet(Subscription::new);
        local.setOwnerToken(original.getOwnerToken());
        local.setPlan(original.getPlan());
        local.setEmail(original.getEmail());
        local.setRazorpaySubscriptionId(original.getRazorpaySubscriptionId());
        local.setStatus(status);
        if (stopImmediately) local.setExpiresAt(Instant.now());
        subs.save(local);
    }

    private void updateCancellation(Payment original, JsonNode providerSubscription) {
        Subscription local = subs.findByOwnerToken(original.getOwnerToken()).orElseGet(Subscription::new);
        local.setOwnerToken(original.getOwnerToken());
        local.setPlan(original.getPlan());
        local.setEmail(original.getEmail());
        local.setRazorpaySubscriptionId(original.getRazorpaySubscriptionId());
        boolean cycleEnd = providerSubscription.path("cancel_at_cycle_end").asBoolean(false);
        if (cycleEnd && local.getExpiresAt() != null && local.getExpiresAt().isAfter(Instant.now())) {
            local.setStatus("CANCEL_AT_CYCLE_END");
        } else {
            local.setStatus("CANCELLED");
            local.setExpiresAt(Instant.now());
        }
        subs.save(local);
    }

    private static Instant epoch(long seconds) {
        if (seconds <= 0) return null;
        try { return Instant.ofEpochSecond(seconds); } catch (RuntimeException e) { return null; }
    }
    private static String title(String value) { return value.substring(0, 1) + value.substring(1).toLowerCase(); }
}
