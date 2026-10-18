package com.auravision.service;

import com.auravision.model.Generation;
import com.auravision.repo.GenerationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;


/** Runs generations in the background so HTTP requests return immediately. */
@Component
@RequiredArgsConstructor
@Slf4j
public class GenerationWorker {
    private final GenerationRepository repo;
    private final GeminiClient gemini;
    private final CloudflareImageClient cloudflare;
    private final GenerationCreditService creditService;
    private final MediaStorageService mediaStorage;
    @Value("${app.storage-dir}") private String storageDir;

    @Async
    public void process(Long id) {
        Generation g = repo.findById(id).orElse(null);
        if (g == null) return;
        try {
            g.setStatus(Generation.Status.RUNNING);
            GeminiClient.Media media;
            if (g.getType() == Generation.Type.IMAGE) {
                if (g.getModel() == null || g.getModel().isBlank()) g.setModel(CloudflareImageClient.MODEL_KEY);
                repo.save(g);
                media = cloudflare.generateImage(g.getPrompt(), g.getAspectRatio(), g.getModel());
            } else {
                g.setModel(gemini.videoModel());
                g.setOperationName(gemini.startVideo(g.getPrompt(), g.getAspectRatio()));
                repo.save(g);
                media = gemini.awaitVideo(g.getOperationName());
            }
            String filename = id + "." + extension(media.mime());
            g.setFilePath(mediaStorage.store(id, filename, media.data(), media.mime()));
            g.setMimeType(media.mime());
            g.setStatus(Generation.Status.DONE);
            g.setError(null);
        } catch (Exception e) {
            log.error("Generation {} failed", id, e);
            g.setStatus(Generation.Status.FAILED);
            // Provider error bodies can contain implementation details; keep them in server logs only.
            g.setError("Generation failed. Your bonus credit will be returned if one was used. Please try again later.");
        }
        repo.save(g);
        if (g.getStatus() == Generation.Status.FAILED) {
            try {
                creditService.refundFailedBonusCredit(id);
            } catch (Exception refundError) {
                log.error("Could not refund bonus credit for failed generation {}", id, refundError);
            }
        }
    }

    private static String extension(String mime) {
        return switch (mime) {
            case "video/mp4" -> "mp4";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "png";
        };
    }
}
