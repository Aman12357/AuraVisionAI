package com.auravision.service;

import com.auravision.model.Payment;
import com.auravision.model.Plan;
import com.auravision.model.RazorpayPlanMapping;
import com.auravision.repo.PaymentRepository;
import com.auravision.repo.RazorpayPlanRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CouponCheckoutService {
    private final BillingService billing;
    private final AdminCouponService coupons;
    private final PaymentRepository payments;
    private final RazorpayPlanRepository razorpayPlans;
    private final RazorpayGateway razorpay;
    private final PlanPricingService pricing;

    @Transactional(readOnly = true)
    public AdminCouponService.CouponQuote quote(String rawCode, Plan plan, boolean yearly) {
        if (plan == null || plan == Plan.FREE)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a paid plan before applying a coupon.");
        return coupons.quote(rawCode, pricing.price(plan, yearly));
    }

    @Transactional
    public BillingService.CheckoutStart createSubscription(String owner, String email, Plan plan, boolean yearly, String rawCode) {
        if (rawCode == null || rawCode.isBlank())
            return billing.createSubscription(owner, email, plan, yearly);
        if (!billing.paymentSetupReady())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, billing.paymentSetupMessage());
        if (plan == null || plan == Plan.FREE)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a paid plan.");
        if (billing.planFor(owner) != Plan.FREE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have a paid plan. Manage it from My Account before starting another subscription.");

        AdminCouponService.CouponQuote quote = coupons.quote(rawCode, pricing.price(plan, yearly));
        Payment purchase = new Payment();
        purchase.setOrderId("AV" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        purchase.setOwnerToken(owner);
        purchase.setPlan(plan);
        purchase.setYearly(yearly);
        purchase.setAmountRupees(quote.discountedAmountRupees());
        purchase.setCouponCode(quote.code());
        purchase.setEmail(email);
        purchase.setStatus(Payment.Status.CREATED);
        purchase = payments.saveAndFlush(purchase);

        try {
            String providerPlanId = razorpay.createPlan(plan, yearly, quote.discountedAmountRupees());
            purchase.setRazorpayPlanId(providerPlanId);
            String subscriptionId = razorpay.createSubscription(providerPlanId, purchase.getId().toString(), plan, yearly, email);
            purchase.setRazorpaySubscriptionId(subscriptionId);
            purchase.setStatus(Payment.Status.PENDING);
            payments.save(purchase);
            // Reserve one use when a real provider checkout is created, not when the page merely previews the code.
            coupons.recordRedemption(quote.code());
            return new BillingService.CheckoutStart(purchase.getOrderId(), subscriptionId, razorpay.publicKeyId(), email,
                plan.name(), yearly, quote.discountedAmountRupees(),
                title(plan.name()) + (yearly ? " yearly" : " monthly") + " subscription (" + quote.discountPercent() + "% coupon applied)");
        } catch (RuntimeException e) {
            purchase.setStatus(Payment.Status.FAILED);
            payments.save(purchase);
            throw e;
        }
    }

    private static String title(String value) { return value.substring(0, 1) + value.substring(1).toLowerCase(); }
}
