package com.auravision.service;

import com.auravision.model.AccountUser;
import com.auravision.model.Generation;
import com.auravision.model.Plan;
import com.auravision.repo.AccountUserRepository;
import com.auravision.repo.GenerationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class GenerationService {
    private static final int IMAGE_CREDIT_COST = 2;
    private static final List<Generation.Status> ACTIVE = List.of(Generation.Status.PENDING, Generation.Status.RUNNING);
    private static final Set<String> IMAGE_RATIOS = Set.of("1:1", "16:9", "9:16", "4:3", "3:4");
    private static final Set<String> VIDEO_RATIOS = Set.of("16:9", "9:16");
    private static final Set<String> IMAGE_MODELS = Set.of(
            "cloudflare-flux-schnell",
            "cloudflare-sdxl-lightning",
            "cloudflare-sdxl-base",
            "cloudflare-dreamshaper"
    );

    public record Usage(Plan plan, int imagesLeft, int videosLeft, Instant expiresAt) {}

    private final GenerationRepository repo;
    private final GenerationWorker worker;
    private final BillingService billing;
    private final AccountUserRepository users;
    private final MediaStorageService mediaStorage;
    @Value("${app.max-active-jobs}") private int maxActive;

    @Transactional
    public Generation submit(String owner, Generation.Type type, String prompt, String ratio, String requestedModel) {
        String selectedModel = type == Generation.Type.IMAGE
                ? (requestedModel == null || requestedModel.isBlank() ? CloudflareImageClient.MODEL_KEY : requestedModel.trim())
                : null;
        if (type == Generation.Type.IMAGE && !IMAGE_MODELS.contains(selectedModel))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported image model. Choose one of the available AuraVision Cloudflare models.");
        if (repo.countByOwnerTokenAndStatusIn(owner, ACTIVE) >= maxActive)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "You already have " + maxActive + " generations in progress. Wait for one to finish.");

        Plan plan = billing.planFor(owner);
        if (type == Generation.Type.IMAGE && !modelAllowedForPlan(plan, selectedModel)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "This image model is not included in your current plan. Free unlocks AuraVision Fast, PLUS unlocks Lite, PRO unlocks Standard, and ULTRA unlocks Pro.");
        }
        int creditCost = type == Generation.Type.IMAGE ? IMAGE_CREDIT_COST : 1;
        // Daily image allowances are measured in images in Plan, while usage is measured in credits.
        int limit = type == Generation.Type.IMAGE ? plan.imagesPerDay * IMAGE_CREDIT_COST : plan.videosPerDay;
        long used = usedToday(owner, type);
        boolean bonusCreditUsed = false;
        if (used + creditCost > limit) {
            if (!consumeBonusCredit(owner, type)) {
                if (limit == 0)
                    throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Video generation needs a paid plan or an available bonus video credit.");
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Daily limit reached (" + limit + " per day on the " + plan + " plan). It resets at midnight UTC.");
            }
            bonusCreditUsed = true;
        }

        Generation g = new Generation();
        g.setOwnerToken(owner);
        g.setType(type);
        g.setPrompt(prompt.trim());
        g.setAspectRatio(normalise(type, ratio));
        if (type == Generation.Type.IMAGE) g.setModel(selectedModel);
        g.setBonusCreditUsed(bonusCreditUsed);
        g = repo.save(g);
        Long generationId = g.getId();
        // Do not let the async worker race the transaction commit and miss the new row.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { worker.process(generationId); }
        });
        return g;
    }

    public Usage usage(String owner) {
        Plan p = billing.planFor(owner);
        AccountUser user = users.findByOwnerToken(owner).orElse(null);
        int bonusImages = user == null ? 0 : user.getBonusImageCredits();
        int bonusVideos = user == null ? 0 : user.getBonusVideoCredits();
        return new Usage(p,
                (int) Math.max(0, (long) p.imagesPerDay * IMAGE_CREDIT_COST - usedToday(owner, Generation.Type.IMAGE)) + bonusImages,
                (int) Math.max(0, p.videosPerDay - usedToday(owner, Generation.Type.VIDEO)) + bonusVideos,
                billing.expiry(owner).orElse(null));
    }

    private static boolean modelAllowedForPlan(Plan plan, String model) {
        return switch (plan) {
            case FREE -> "cloudflare-flux-schnell".equals(model);
            case PLUS -> "cloudflare-sdxl-lightning".equals(model);
            case PRO -> "cloudflare-sdxl-base".equals(model);
            case ULTRA -> "cloudflare-dreamshaper".equals(model);
        };
    }

    private boolean consumeBonusCredit(String owner, Generation.Type type) {
        AccountUser user = users.findByOwnerToken(owner).orElse(null);
        if (user == null) return false;
        // Image generations consume two bonus image credits per successful request.
        if (type == Generation.Type.IMAGE && user.getBonusImageCredits() >= 2) {
            user.setBonusImageCredits(user.getBonusImageCredits() - 2);
            users.save(user);
            return true;
        }
        if (type == Generation.Type.VIDEO && user.getBonusVideoCredits() > 0) {
            user.setBonusVideoCredits(user.getBonusVideoCredits() - 1);
            users.save(user);
            return true;
        }
        return false;
    }

    public List<Generation> latest(String owner) { return repo.findTop30ByOwnerTokenOrderByCreatedAtDesc(owner); }

    public Generation get(String owner, Long id) {
        return repo.findByIdAndOwnerToken(id, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
    }

    public void delete(String owner, Long id) {
        Generation g = get(owner, id);
        if (g.getFilePath() != null) {
            try { mediaStorage.delete(g.getFilePath()); }
            catch (Exception ignored) { /* Keep delete available if object storage is temporarily unavailable. */ }
        }
        repo.delete(g);
    }

    private long usedToday(String owner, Generation.Type type) {
        Instant start = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        long generated = repo.countByOwnerTokenAndTypeAndCreatedAtAfterAndStatusNot(owner, type, start, Generation.Status.FAILED);
        return type == Generation.Type.IMAGE ? generated * IMAGE_CREDIT_COST : generated;
    }

    private static String normalise(Generation.Type type, String ratio) {
        if (type == Generation.Type.VIDEO) return VIDEO_RATIOS.contains(ratio) ? ratio : "16:9";
        return IMAGE_RATIOS.contains(ratio) ? ratio : "1:1";
    }
}
