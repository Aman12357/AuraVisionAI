package com.auravision.service;

import com.auravision.model.Generation;
import com.auravision.model.Plan;
import com.auravision.repo.GenerationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class GenerationService {
    private static final List<Generation.Status> ACTIVE = List.of(Generation.Status.PENDING, Generation.Status.RUNNING);
    private static final Set<String> IMAGE_RATIOS = Set.of("1:1", "16:9", "9:16", "4:3", "3:4");
    private static final Set<String> VIDEO_RATIOS = Set.of("16:9", "9:16");
    private static final Set<String> IMAGE_MODELS = Set.of(
            "gemini-3-pro-image",
            "gemini-3.1-flash-image",
            "gemini-3.1-flash-lite-image"
    );

    public record Usage(Plan plan, int imagesLeft, int videosLeft, Instant expiresAt) {}

    private final GenerationRepository repo;
    private final GenerationWorker worker;
    private final BillingService billing;
    @Value("${app.max-active-jobs}") private int maxActive;

    public Generation submit(String owner, Generation.Type type, String prompt, String ratio, String requestedModel) {
        String selectedModel = type == Generation.Type.IMAGE
                ? (requestedModel == null || requestedModel.isBlank() ? "gemini-3.1-flash-image" : requestedModel.trim())
                : null;
        if (type == Generation.Type.IMAGE && !IMAGE_MODELS.contains(selectedModel))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported image model. Choose Nano Banana Pro, Nano Banana 2, or Nano Banana 2 Lite.");
        if (repo.countByOwnerTokenAndStatusIn(owner, ACTIVE) >= maxActive)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "You already have " + maxActive + " generations in progress. Wait for one to finish.");

        Plan plan = billing.planFor(owner);
        int limit = type == Generation.Type.VIDEO ? plan.videosPerDay : plan.imagesPerDay;
        if (limit == 0)
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Video generation needs a paid plan. Choose Upgrade to continue.");
        if (usedToday(owner, type) >= limit)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Daily limit reached (" + limit + " per day on the " + plan + " plan). It resets at midnight UTC.");

        Generation g = new Generation();
        g.setOwnerToken(owner);
        g.setType(type);
        g.setPrompt(prompt.trim());
        g.setAspectRatio(normalise(type, ratio));
        if (type == Generation.Type.IMAGE) g.setModel(selectedModel);
        g = repo.save(g);
        worker.process(g.getId());
        return g;
    }

    public Usage usage(String owner) {
        Plan p = billing.planFor(owner);
        return new Usage(p,
                (int) Math.max(0, p.imagesPerDay - usedToday(owner, Generation.Type.IMAGE)),
                (int) Math.max(0, p.videosPerDay - usedToday(owner, Generation.Type.VIDEO)),
                billing.expiry(owner).orElse(null));
    }

    public List<Generation> latest(String owner) { return repo.findTop30ByOwnerTokenOrderByCreatedAtDesc(owner); }

    public Generation get(String owner, Long id) {
        return repo.findByIdAndOwnerToken(id, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
    }

    public void delete(String owner, Long id) {
        Generation g = get(owner, id);
        if (g.getFilePath() != null) {
            try { Files.deleteIfExists(Path.of(g.getFilePath())); } catch (IOException ignored) {}
        }
        repo.delete(g);
    }

    private long usedToday(String owner, Generation.Type type) {
        Instant start = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        return repo.countByOwnerTokenAndTypeAndCreatedAtAfterAndStatusNot(owner, type, start, Generation.Status.FAILED);
    }

    private static String normalise(Generation.Type type, String ratio) {
        if (type == Generation.Type.VIDEO) return VIDEO_RATIOS.contains(ratio) ? ratio : "16:9";
        return IMAGE_RATIOS.contains(ratio) ? ratio : "1:1";
    }
}
