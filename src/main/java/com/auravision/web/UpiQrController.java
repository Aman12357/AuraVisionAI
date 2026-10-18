package com.auravision.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Read-only proxy for the public AuraPay QR image. This endpoint never verifies payments,
 * accepts payment screenshots, or grants credits.
 */
@RestController
@RequestMapping("/api/checkout")
public class UpiQrController {
    private final RestClient client;

    public UpiQrController(@Value("${aurapay.base-url:https://aurapay-jp4s.onrender.com}") String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (normalized.isBlank()) normalized = "https://aurapay-jp4s.onrender.com";
        this.client = RestClient.builder().baseUrl(normalized).build();
    }

    @GetMapping("/upi-qr")
    public ResponseEntity<byte[]> getUpiQr() {
        try {
            ResponseEntity<byte[]> response = client.get().uri("/api/payment-qr")
                .retrieve().toEntity(byte[].class);
            byte[] body = response.getBody();
            MediaType type = response.getHeaders().getContentType();
            if (body == null || body.length == 0 || type == null || !type.getType().equals("image")) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            }
            return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.noCache())
                .header(HttpHeaders.X_CONTENT_TYPE_OPTIONS, "nosniff")
                .body(body);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return ResponseEntity.notFound().build();
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        } catch (RestClientException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
