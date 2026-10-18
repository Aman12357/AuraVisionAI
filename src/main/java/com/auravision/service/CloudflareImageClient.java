package com.auravision.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * All branded AuraVision image modes are routed to Cloudflare Workers AI.
 * The account ID and API token remain server-side environment variables only.
 */
@Component
public class CloudflareImageClient {
    public static final String MODEL_KEY = "cloudflare-flux-schnell";
    private static final String DEFAULT_MODEL = "@cf/black-forest-labs/flux-1-schnell";
    private static final String BASE = "https://api.cloudflare.com/client/v4/accounts/";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper om;
    private final String accountId;
    private final String apiToken;
    private final String model;

    public CloudflareImageClient(
            ObjectMapper om,
            @Value("${cloudflare.account-id:}") String accountId,
            @Value("${cloudflare.api-token:}") String apiToken,
            @Value("${cloudflare.image-model:" + DEFAULT_MODEL + "}") String model) {
        this.om = om;
        this.accountId = accountId == null ? "" : accountId.trim();
        this.apiToken = apiToken == null ? "" : apiToken.trim();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
    }

    public GeminiClient.Media generateImage(String prompt, String aspectRatio) throws Exception {
        return generateImage(prompt, aspectRatio, MODEL_KEY);
    }

    public GeminiClient.Media generateImage(String prompt, String aspectRatio, String modelKey) throws Exception {
        String cloudflareModel = switch (modelKey == null ? MODEL_KEY : modelKey) {
            case "cloudflare-flux-schnell" -> "@cf/black-forest-labs/flux-1-schnell";
            case "cloudflare-sdxl-lightning" -> "@cf/bytedance/stable-diffusion-xl-lightning";
            case "cloudflare-sdxl-base" -> "@cf/stabilityai/stable-diffusion-xl-base-1.0";
            case "cloudflare-dreamshaper" -> "@cf/lykon/dreamshaper-8-lcm";
            default -> throw new IllegalArgumentException("Unsupported AuraVision Cloudflare model.");
        };
        if (accountId.isBlank() || apiToken.isBlank()) {
            throw new IllegalStateException(
                    "Cloudflare image generation is not configured yet. In Render > Environment, add CLOUDFLARE_ACCOUNT_ID and CLOUDFLARE_API_TOKEN. Keep the token private.");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Please enter an image description.");
        }
        if (prompt.length() > 2048) {
            throw new IllegalArgumentException("The image prompt must be 2,048 characters or fewer for Cloudflare Workers AI.");
        }

        ObjectNode body = om.createObjectNode();
        body.put("prompt", prompt);
        if ("@cf/black-forest-labs/flux-1-schnell".equals(cloudflareModel)) body.put("steps", 4);

        String url = BASE + accountId + "/ai/run/" + cloudflareModel;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, image/jpeg, image/png")
                .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)))
                .build();

        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
        byte[] responseBytes = response.body();

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = cloudflareError(responseBytes);
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new IllegalStateException("Cloudflare rejected the API token/account (HTTP " + response.statusCode() + "): " + message
                        + " Check CLOUDFLARE_ACCOUNT_ID and a token with Workers AI Read and Workers AI Run/Edit permissions.");
            }
            if (response.statusCode() == 429) {
                throw new IllegalStateException("Cloudflare Workers AI rate/free daily allowance reached (HTTP 429): " + message
                        + " Try again after the daily quota resets.");
            }
            throw new IllegalStateException("Cloudflare image generation failed (HTTP " + response.statusCode() + "): " + message);
        }

        if (contentType.toLowerCase().startsWith("image/")) {
            return new GeminiClient.Media(responseBytes, contentType.split(";")[0].trim());
        }

        JsonNode json;
        try {
            json = om.readTree(responseBytes);
        } catch (Exception ex) {
            if (looksLikeJpeg(responseBytes)) return new GeminiClient.Media(responseBytes, "image/jpeg");
            if (looksLikePng(responseBytes)) return new GeminiClient.Media(responseBytes, "image/png");
            throw new IllegalStateException("Cloudflare returned an unexpected image response format.");
        }

        String encoded = json.path("result").path("image").asText("");
        if (encoded.isBlank()) encoded = json.path("image").asText("");
        if (encoded.isBlank()) {
            String message = cloudflareError(responseBytes);
            throw new IllegalStateException(message.isBlank()
                    ? "Cloudflare Workers AI returned no image. Please try a different prompt."
                    : "Cloudflare did not return an image: " + message);
        }

        String mime = "image/jpeg";
        if (encoded.startsWith("data:")) {
            int semicolon = encoded.indexOf(';');
            int comma = encoded.indexOf(',');
            if (semicolon > 5) mime = encoded.substring(5, semicolon);
            if (comma >= 0) encoded = encoded.substring(comma + 1);
        }

        try {
            return new GeminiClient.Media(Base64.getDecoder().decode(encoded), mime);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Cloudflare returned invalid base64 image data.");
        }
    }

    private String cloudflareError(byte[] bytes) {
        try {
            JsonNode json = om.readTree(bytes);
            JsonNode errors = json.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                String message = errors.get(0).path("message").asText("");
                if (!message.isBlank()) return message;
            }
            String resultMessage = json.path("result").path("error").asText("");
            if (!resultMessage.isBlank()) return resultMessage;
            return json.path("message").asText(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static boolean looksLikeJpeg(byte[] b) {
        return b != null && b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff;
    }

    private static boolean looksLikePng(byte[] b) {
        return b != null && b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 0x50 && b[2] == 0x4e && b[3] == 0x47
                && b[4] == 0x0d && b[5] == 0x0a && b[6] == 0x1a && b[7] == 0x0a;
    }
}
