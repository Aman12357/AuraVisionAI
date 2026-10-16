package com.auravision.service;

import com.auravision.model.Plan;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@Service
public class RazorpayGateway {
    @Value("$" + "{razorpay.key-id:}") private String keyId;
    @Value("$" + "{razorpay.key-secret:}") private String keySecret;
    @Value("$" + "{razorpay.webhook-secret:}") private String webhookSecret;

    public boolean isConfigured() { return present(keyId) && present(keySecret) && present(webhookSecret); }
    public String publicKeyId() { requireConfigured(); return keyId.trim(); }

    public void requireConfigured() {
        if (!isConfigured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Razorpay is not configured. In Render Environment set RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET and RAZORPAY_WEBHOOK_SECRET. Keep secret values private.");
    }

    public String createOrder(int amountRupees, String internalPurchaseId, Plan plan, boolean yearly, String email) {
        requireConfigured();
        if (amountRupees < 1 || internalPurchaseId == null || internalPurchaseId.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A positive amount and purchase reference are required.");
        Map<String, Object> request = Map.of(
            "amount", Math.multiplyExact(amountRupees, 100),
            "currency", "INR",
            "receipt", internalPurchaseId,
            "notes", Map.of("product", "AuraVision AI", "plan", plan.name(),
                "access_period", yearly ? "365 days" : "30 days", "customer_email", email)
        );
        JsonNode response = post("/orders", request);
        String id = response.path("id").asText("");
        if (id.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Razorpay did not return an order id.");
        return id;
    }

    public boolean verifyOrderSignature(String orderId, String paymentId, String signature) {
        if (!present(keySecret) || !present(orderId) || !present(paymentId) || !present(signature)) return false;
        return constantTimeEquals(hmacHex((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8), keySecret), signature.trim());
    }

    public String createPlan(Plan plan, boolean yearly) {
        return createPlan(plan, yearly, plan.price(yearly));
    }

    public String createPlan(Plan plan, boolean yearly, int amountRupees) {
        requireConfigured();
        if (plan == null || plan == Plan.FREE || amountRupees < 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A paid plan and positive price are required.");
        String period = yearly ? "yearly" : "monthly";
        Map<String, Object> request = Map.of(
            "period", period,
            "interval", 1,
            "item", Map.of(
                "name", "AuraVision AI " + title(plan.name()) + " " + period,
                "amount", Math.multiplyExact(amountRupees, 100),
                "currency", "INR",
                "description", "AuraVision AI " + title(plan.name()) + " recurring subscription"
            ),
            "notes", Map.of("product", "AuraVision AI", "plan", plan.name(), "billing", period)
        );
        JsonNode response = post("/plans", request);
        String id = response.path("id").asText("");
        if (id.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Razorpay did not return a plan id.");
        return id;
    }

    public String createSubscription(String planId, String internalPurchaseId, Plan plan, boolean yearly, String email) {
        requireConfigured();
        Map<String, Object> request = Map.of(
            "plan_id", planId,
            "total_count", yearly ? 100 : 1200,
            "quantity", 1,
            "customer_notify", true,
            "notes", Map.of(
                "auravision_purchase_id", internalPurchaseId,
                "auravision_plan", plan.name(),
                "auravision_billing", yearly ? "yearly" : "monthly",
                "customer_email", email
            )
        );
        JsonNode response = post("/subscriptions", request);
        String id = response.path("id").asText("");
        if (id.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Razorpay did not return a subscription id.");
        return id;
    }

    public void cancelSubscription(String subscriptionId, boolean atCycleEnd) {
        requireConfigured();
        post("/subscriptions/" + subscriptionId + "/cancel", Map.of("cancel_at_cycle_end", atCycleEnd));
    }

    public boolean verifySubscriptionSignature(String paymentId, String subscriptionId, String signature) {
        if (!present(keySecret) || !present(paymentId) || !present(subscriptionId) || !present(signature)) return false;
        return constantTimeEquals(hmacHex((paymentId + "|" + subscriptionId).getBytes(StandardCharsets.UTF_8), keySecret), signature.trim());
    }

    public boolean verifyWebhookSignature(byte[] rawBody, String signature) {
        if (!present(webhookSecret) || rawBody == null || !present(signature)) return false;
        return constantTimeEquals(hmacHex(rawBody, webhookSecret), signature.trim());
    }

    private JsonNode post(String path, Object body) {
        try {
            JsonNode response = client().post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(JsonNode.class);
            if (response == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Razorpay returned an empty response.");
            return response;
        } catch (RestClientResponseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Razorpay rejected the request (HTTP " + e.getStatusCode().value()
                + "). Check your Test/Live keys and that Subscriptions is enabled.");
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not connect to Razorpay. Please retry.");
        }
    }

    private RestClient client() {
        requireConfigured();
        return RestClient.builder().baseUrl("https://api.razorpay.com/v1")
            .defaultHeaders(headers -> headers.setBasicAuth(keyId.trim(), keySecret.trim()))
            .build();
    }

    private static String hmacHex(byte[] message, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message));
        } catch (Exception e) { throw new IllegalStateException("Unable to verify payment signature.", e); }
    }
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static String title(String value) { return value.substring(0, 1) + value.substring(1).toLowerCase(); }
}
