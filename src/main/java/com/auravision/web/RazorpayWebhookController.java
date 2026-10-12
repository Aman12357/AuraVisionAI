package com.auravision.web;

import com.auravision.service.BillingService;
import com.auravision.service.RazorpayGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments/razorpay")
@RequiredArgsConstructor
public class RazorpayWebhookController {
    private final RazorpayGateway razorpay;
    private final BillingService billing;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/webhook", consumes = "application/json")
    public ResponseEntity<Void> webhook(@RequestBody byte[] rawBody,
        @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        if (!razorpay.verifyWebhookSignature(rawBody, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            JsonNode event = objectMapper.readTree(rawBody);
            billing.handleWebhook(event);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            // Non-2xx lets Razorpay retry transient DB/processing failures.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
