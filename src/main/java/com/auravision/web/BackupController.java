package com.auravision.web;

import com.auravision.model.AccountUser;
import com.auravision.model.Generation;
import com.auravision.model.Payment;
import com.auravision.model.RazorpayPlanMapping;
import com.auravision.model.Subscription;
import com.auravision.repo.AccountUserRepository;
import com.auravision.repo.GenerationRepository;
import com.auravision.repo.PaymentRepository;
import com.auravision.repo.RazorpayPlanRepository;
import com.auravision.repo.SubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Private machine-to-machine export. Password hashes and sessions are never exported. */
@RestController
@RequestMapping("/api/admin/backup")
@RequiredArgsConstructor
public class BackupController {
    private static final Pattern MEDIA_NAME = Pattern.compile("^\\d+\\.(png|jpg|webp|mp4)$");
    private final AccountUserRepository users;
    private final GenerationRepository generations;
    private final PaymentRepository payments;
    private final SubscriptionRepository subscriptions;
    private final RazorpayPlanRepository razorpayPlans;
    private final ObjectMapper objectMapper;

    @Value("$" + "{BACKUP_API_TOKEN:}") private String backupToken;
    @Value("$" + "{app.storage-dir:./storage}") private String storageDir;

    @PostMapping(value = "/export", produces = "application/zip")
    public ResponseEntity<StreamingResponseBody> export(HttpServletRequest request) throws Exception {
        verifyRequest(request);
        List<Map<String, Object>> userRows = new ArrayList<>();
        for (AccountUser user : users.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", user.getId());
            row.put("email", user.getEmail());
            row.put("ownerId", user.getOwnerToken());
            row.put("createdAt", user.getCreatedAt());
            userRows.add(row);
        }

        Path root = Path.of(storageDir).toAbsolutePath().normalize();
        List<Map<String, Object>> generationRows = new ArrayList<>();
        List<Path> mediaFiles = new ArrayList<>();
        for (Generation g : generations.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", g.getId());
            row.put("type", g.getType());
            row.put("prompt", g.getPrompt());
            row.put("aspectRatio", g.getAspectRatio());
            row.put("model", g.getModel());
            row.put("status", g.getStatus());
            row.put("mimeType", g.getMimeType());
            row.put("ownerId", g.getOwnerToken());
            row.put("createdAt", g.getCreatedAt());
            row.put("updatedAt", g.getUpdatedAt());
            String mediaName = null;
            if (g.getFilePath() != null && g.getId() != null) {
                try {
                    Path file = Path.of(g.getFilePath()).toAbsolutePath().normalize();
                    String candidate = file.getFileName() == null ? "" : file.getFileName().toString();
                    if (file.startsWith(root) && MEDIA_NAME.matcher(candidate).matches()
                            && candidate.startsWith(g.getId() + ".") && Files.isRegularFile(file)) {
                        mediaName = candidate;
                        mediaFiles.add(file);
                    }
                } catch (RuntimeException ignored) {
                    // Invalid paths are omitted rather than trusted.
                }
            }
            row.put("mediaFile", mediaName);
            generationRows.add(row);
        }

        List<Map<String, Object>> paymentRows = new ArrayList<>();
        for (Payment p : payments.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("orderId", p.getOrderId());
            row.put("ownerId", p.getOwnerToken());
            row.put("plan", p.getPlan());
            row.put("yearly", p.isYearly());
            row.put("amountRupees", p.getAmountRupees());
            row.put("email", p.getEmail());
            row.put("method", p.getMethod());
            row.put("status", p.getStatus());
            row.put("createdAt", p.getCreatedAt());
            row.put("paidAt", p.getPaidAt());
            paymentRows.add(row);
        }

        List<Map<String, Object>> subscriptionRows = new ArrayList<>();
        for (Subscription s : subscriptions.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("ownerId", s.getOwnerToken());
            row.put("plan", s.getPlan());
            row.put("email", s.getEmail());
            row.put("status", s.getStatus());
            row.put("expiresAt", s.getExpiresAt());
            subscriptionRows.add(row);
        }

        List<Map<String, Object>> planRows = new ArrayList<>();
        for (RazorpayPlanMapping p : razorpayPlans.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("plan", p.getPlan());
            row.put("yearly", p.isYearly());
            row.put("createdAt", p.getCreatedAt());
            planRows.add(row);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", 1);
        manifest.put("createdAt", Instant.now());
        manifest.put("users", userRows);
        manifest.put("generations", generationRows);
        manifest.put("payments", paymentRows);
        manifest.put("subscriptions", subscriptionRows);
        manifest.put("razorpayPlans", planRows);
        byte[] manifestBytes = objectMapper.writeValueAsBytes(manifest);

        StreamingResponseBody body = output -> {
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(manifestBytes);
                zip.closeEntry();
                for (Path file : mediaFiles) {
                    String name = file.getFileName().toString();
                    if (!MEDIA_NAME.matcher(name).matches()) continue;
                    zip.putNextEntry(new ZipEntry("media/" + name));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
                zip.finish();
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"auravision-backup.zip\"")
                .body(body);
    }

    private void verifyRequest(HttpServletRequest request) {
        if (backupToken == null || backupToken.length() < 32) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        String proto = request.getHeader("X-Forwarded-Proto");
        if (!request.isSecure() && (proto == null || !proto.equalsIgnoreCase("https"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "HTTPS required");
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        byte[] expected = backupToken.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = header.substring(7).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
    }
}
