package com.auravision.web;

import com.auravision.model.Payment;
import com.auravision.model.Plan;
import com.auravision.service.BillingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/checkout")
@RequiredArgsConstructor
public class CheckoutController {
    private final BillingService billing;

    public record PlanView(String id, String name, int monthly, int yearly, int imagesPerDay, int videosPerDay) {}
    public record OrderRequest(@NotNull Plan plan, boolean yearly, @NotBlank @Email String email) {}
    public record OrderView(String orderId, Plan plan, boolean yearly, int amount) {}
    public record PayRequest(@NotBlank String method) {}
    public record PayView(String status, String orderId, String txnRef, Plan plan, Instant expiresAt) {}

    @GetMapping("/plans")
    public List<PlanView> plans() {
        return Arrays.stream(Plan.values()).filter(p -> p != Plan.FREE)
                .map(p -> new PlanView(p.name(), p.name().charAt(0) + p.name().substring(1).toLowerCase(),
                        p.price(false), p.price(true), p.imagesPerDay, p.videosPerDay)).toList();
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView createOrder(@RequestAttribute(OwnerFilter.ATTR) String owner, @Valid @RequestBody OrderRequest r) {
        Payment p = billing.createOrder(owner, r.plan(), r.yearly(), r.email());
        return new OrderView(p.getOrderId(), p.getPlan(), p.isYearly(), p.getAmountRupees());
    }

    @PostMapping("/orders/{orderId}/pay")
    public PayView pay(@RequestAttribute(OwnerFilter.ATTR) String owner, @PathVariable String orderId,
                       @Valid @RequestBody PayRequest r) {
        Payment p = billing.pay(owner, orderId, r.method());
        return new PayView(p.getStatus().name(), p.getOrderId(), p.getTxnRef(), p.getPlan(),
                billing.expiry(owner).orElse(null));
    }
}
