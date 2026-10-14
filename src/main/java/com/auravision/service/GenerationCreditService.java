package com.auravision.service;

import com.auravision.model.AccountUser;
import com.auravision.model.Generation;
import com.auravision.repo.AccountUserRepository;
import com.auravision.repo.GenerationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Returns a one-off bonus credit when its generation fails, at most once. */
@Service
@RequiredArgsConstructor
public class GenerationCreditService {
    private final GenerationRepository generations;
    private final AccountUserRepository users;

    @Transactional
    public void refundFailedBonusCredit(Long generationId) {
        Generation generation = generations.findById(generationId).orElse(null);
        if (generation == null
                || generation.getStatus() != Generation.Status.FAILED
                || !Boolean.TRUE.equals(generation.getBonusCreditUsed())
                || Boolean.TRUE.equals(generation.getBonusCreditRefunded())) {
            return;
        }

        AccountUser user = users.findByOwnerToken(generation.getOwnerToken()).orElse(null);
        if (user == null) {
            // Leave the marker unset so an administrator can diagnose the missing account safely.
            return;
        }

        if (generation.getType() == Generation.Type.IMAGE) {
            user.setBonusImageCredits(user.getBonusImageCredits() + 1);
        } else {
            user.setBonusVideoCredits(user.getBonusVideoCredits() + 1);
        }
        generation.setBonusCreditRefunded(true);
        users.save(user);
        generations.save(generation);
    }
}
