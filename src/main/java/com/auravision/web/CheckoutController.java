package com.auravision.web;

import com.auravision.model.Plan;
import com.auravision.service.BillingService;
import com.auravision.service.PlanPricingService;
import com.auravision.service.RazorpayGateway;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/checkout")
@RequiredArgsConstructor
public class CheckoutController {
    private final BillingService billing;
    private final RazorpayGateway razorpay;
    private final PlanPricingService pricing;

    public record PlanView(String id, String name, int monthly, int yearly, int imagesPerDay, int videosPerDay) {}
    public record SubscriptionRequest(@NotNull Plan plan, boolean yearly) {}
    public record VerifyRequest(@NotBlank String orderId, @NotBlank String razorpayPaymentId,
                                @NotBlank String razorpaySubscriptionId, @NotBlank String razorpaySignature) {}
    public record OneTimeVerifyRequest(@NotBlank String orderId, @NotBlank String razorpayOrderId,
                                       @NotBlank String razorpayPaymentId, @NotBlank String razorpaySignature) {}
    public record CheckoutConfig(boolean ready, boolean databaseReady, boolean razorpayConfigured,
                                 String provider, String message) {}
    public record CancelView(String status, Instant expiresAt, String message) {}

    @GetMapping("/plans")
    public List<PlanView> plans() {
        return Arrays.stream(Plan.values()).filter(p -> p != Plan.FREE)
            .map(p -> new PlanView(p.name(), title(p.name()), pricing.price(p, false), pricing.price(p, true),
                p.imagesPerDay, p.videosPerDay)).toList();
    }

    @GetMapping("/config")
    public CheckoutConfig config() {
        return new CheckoutConfig(billing.paymentSetupReady(), billing.isPersistentDatabaseConfigured(),
            razorpay.isConfigured(), "Razorpay", billing.paymentSetupMessage());
    }

    @PostMapping("/one-time")
    public BillingService.OneTimeCheckoutStart createOneTimeOrder(HttpServletRequest request, @Valid @RequestBody SubscriptionRequest body) {
        return billing.createOneTimeOrder(requireAccount(request), "", body.plan(), body.yearly());
    }

    @PostMapping("/verify-one-time")
    public BillingService.PurchaseStatus verifyOneTime(HttpServletRequest request, @Valid @RequestBody OneTimeVerifyRequest body) {
        return billing.verifyOneTimeCheckout(requireAccount(request), body.orderId(), body.razorpayOrderId(), body.razorpayPaymentId(), body.razorpaySignature());
    }

    @PostMapping("/subscriptions")
    public BillingService.CheckoutStart createSubscription(HttpServletRequest request,
                                                            @Valid @RequestBody SubscriptionRequest body) {
        throw new ResponseStatusException(HttpStatus.GONE,
            "New recurring subscriptions are currently disabled. Existing subscriptions are not automatically cancelled.");
    }

    @PostMapping("/verify")
    public BillingService.PurchaseStatus verify(HttpServletRequest request, @Valid @RequestBody VerifyRequest body) {
        return billing.verifyCheckout(requireAccount(request), body.orderId(), body.razorpayPaymentId(),
            body.razorpaySubscriptionId(), body.razorpaySignature());
    }

    @GetMapping("/orders/{orderId}")
    public BillingService.PurchaseStatus status(HttpServletRequest request, @PathVariable String orderId) {
        return billing.purchaseStatus(requireAccount(request), orderId);
    }

    @GetMapping("/history")
    public List<BillingService.HistoryItem> history(HttpServletRequest request) {
        return billing.history(requireAccount(request));
    }

    @PostMapping("/subscriptions/cancel")
    public CancelView cancel(HttpServletRequest request) {
        Instant expiry = billing.cancelAtCycleEnd(requireAccount(request));
        return new CancelView("CANCEL_AT_CYCLE_END", expiry,
            "Cancellation is scheduled for the end of your paid billing period.");
    }

    private static String requireAccount(HttpServletRequest request) {
        Object value = request.getAttribute(OwnerFilter.ACCOUNT_ATTR);
        if (!(value instanceof String owner) || owner.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to manage a subscription.");
        return owner;
    }
    private static String title(String value) { return value.substring(0, 1) + value.substring(1).toLowerCase(); }
}
