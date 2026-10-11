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
import java.time.Duration;
import java.util.Base64;

/** Thin REST client for the Gemini API (images via generateContent, video via Veo predictLongRunning). */
@Component
public class GeminiClient {
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/";

    public record Media(byte[] data, String mime) {}

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper om;
    private final String apiKey, imageModel, videoModel;

    public GeminiClient(ObjectMapper om,
                        @Value("${gemini.api-key}") String apiKey,
                        @Value("${gemini.image-model}") String imageModel,
                        @Value("${gemini.video-model}") String videoModel) {
        this.om = om; this.apiKey = apiKey; this.imageModel = imageModel; this.videoModel = videoModel;
    }

    public String imageModel() { return imageModel; }
    public String videoModel() { return videoModel; }

    // ---------- images ----------
    public Media generateImage(String prompt, String aspectRatio) throws Exception {
        return generateImage(prompt, aspectRatio, imageModel);
    }

    public Media generateImage(String prompt, String aspectRatio, String selectedModel) throws Exception {
        ObjectNode body = om.createObjectNode();
        body.putArray("contents").addObject().putArray("parts").addObject().put("text", prompt);
        body.putObject("generationConfig").putArray("responseModalities").add("TEXT").add("IMAGE");
        ((ObjectNode) body.path("generationConfig")).putObject("imageConfig").put("aspectRatio", aspectRatio);

        JsonNode res = post(selectedModel + ":generateContent", body);
        for (JsonNode part : res.path("candidates").path(0).path("content").path("parts")) {
            JsonNode inline = part.has("inlineData") ? part.get("inlineData") : part.path("inline_data");
            if (inline.has("data")) {
                String mime = inline.has("mimeType") ? inline.get("mimeType").asText() : inline.path("mime_type").asText("image/png");
                return new Media(Base64.getDecoder().decode(inline.get("data").asText()), mime);
            }
        }
        String reason = res.path("promptFeedback").path("blockReason").asText("");
        throw new IllegalStateException(reason.isEmpty() ? "The model returned no image. Try rephrasing the prompt."
                : "The prompt was blocked by safety filters: " + reason);
    }

    // ---------- video ----------
    /** Starts a Veo job and returns the long-running operation name. */
    public String startVideo(String prompt, String aspectRatio) throws Exception {
        ObjectNode body = om.createObjectNode();
        body.putArray("instances").addObject().put("prompt", prompt);
        body.putObject("parameters").put("aspectRatio", aspectRatio);
        JsonNode res = post(videoModel + ":predictLongRunning", body);
        String name = res.path("name").asText("");
        if (name.isEmpty()) throw new IllegalStateException("Gemini did not return a video operation.");
        return name;
    }

    /** Polls the operation until the video is ready (Veo usually takes 1 to 6 minutes), then downloads it. */
    public Media awaitVideo(String operationName) throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis();
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(8000);
            JsonNode op = get(BASE + operationName);
            if (!op.path("done").asBoolean(false)) continue;
            if (op.has("error")) throw new IllegalStateException(op.path("error").path("message").asText("Video generation failed."));
            JsonNode resp = op.path("response");
            JsonNode sample = resp.path("generateVideoResponse").path("generatedSamples").path(0);
            String uri = sample.path("video").path("uri").asText("");
            if (uri.isEmpty()) uri = resp.path("generatedVideos").path(0).path("video").path("uri").asText("");
            if (uri.isEmpty()) {
                String why = resp.path("generateVideoResponse").path("raiMediaFilteredReasons").path(0).asText("");
                throw new IllegalStateException(why.isEmpty() ? "No video was returned." : "Video blocked by safety filters: " + why);
            }
            HttpResponse<byte[]> file = http.send(req(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (file.statusCode() / 100 != 2) throw new IllegalStateException("Could not download the video (HTTP " + file.statusCode() + ").");
            return new Media(file.body(), "video/mp4");
        }
        throw new IllegalStateException("Timed out waiting for the video.");
    }

    // ---------- http helpers ----------
    private HttpRequest.Builder req(String url) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("GEMINI_API_KEY is not set on the server.");
        return HttpRequest.newBuilder(URI.create(url)).header("x-goog-api-key", apiKey).timeout(Duration.ofMinutes(3));
    }

    private JsonNode post(String path, ObjectNode body) throws Exception {
        HttpRequest r = req(BASE + "models/" + path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body))).build();
        return parse(http.send(r, HttpResponse.BodyHandlers.ofString()));
    }

    private JsonNode get(String url) throws Exception {
        return parse(http.send(req(url).GET().build(), HttpResponse.BodyHandlers.ofString()));
    }

    private JsonNode parse(HttpResponse<String> res) throws Exception {
        if (res.statusCode() / 100 != 2) {
            String msg = om.readTree(res.body()).path("error").path("message").asText(res.body());
            throw new IllegalStateException("Gemini API error " + res.statusCode() + ": " + msg);
        }
        return om.readTree(res.body());
    }
}
