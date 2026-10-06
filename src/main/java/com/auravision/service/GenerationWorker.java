package com.auravision.service;

import com.auravision.model.Generation;
import com.auravision.repo.GenerationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/** Runs generations in the background so HTTP requests return immediately. */
@Component
@RequiredArgsConstructor
@Slf4j
public class GenerationWorker {
    private final GenerationRepository repo;
    private final GeminiClient gemini;
    @Value("${app.storage-dir}") private String storageDir;

    @Async
    public void process(Long id) {
        Generation g = repo.findById(id).orElse(null);
        if (g == null) return;
        try {
            g.setStatus(Generation.Status.RUNNING);
            GeminiClient.Media media;
            if (g.getType() == Generation.Type.IMAGE) {
                g.setModel(gemini.imageModel());
                repo.save(g);
                media = gemini.generateImage(g.getPrompt(), g.getAspectRatio());
            } else {
                g.setModel(gemini.videoModel());
                g.setOperationName(gemini.startVideo(g.getPrompt(), g.getAspectRatio()));
                repo.save(g);
                media = gemini.awaitVideo(g.getOperationName());
            }
            Path dir = Path.of(storageDir);
            Files.createDirectories(dir);
            Path file = dir.resolve(id + "." + extension(media.mime()));
            Files.write(file, media.data());
            g.setFilePath(file.toString());
            g.setMimeType(media.mime());
            g.setStatus(Generation.Status.DONE);
            g.setError(null);
        } catch (Exception e) {
            log.error("Generation {} failed", id, e);
            g.setStatus(Generation.Status.FAILED);
            String m = String.valueOf(e.getMessage());
            g.setError(m.length() > 900 ? m.substring(0, 900) : m);
        }
        repo.save(g);
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
