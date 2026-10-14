package com.auravision.service;

import com.auravision.model.Plan;
import com.auravision.model.PlanPricing;
import com.auravision.repo.PlanPricingRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PlanPricingService {
    private final PlanPricingRepository pricing;

    @PostConstruct
    @Transactional
    public void seedDefaults() {
        for (Plan plan : Plan.values()) {
            if (plan == Plan.FREE || pricing.findByPlan(plan).isPresent()) continue;
            PlanPricing row = new PlanPricing();
            row.setPlan(plan);
            row.setMonthlyRupees(plan.monthlyRupees);
            row.setYearlyRupees(plan.price(true));
            pricing.save(row);
        }
    }

    @Transactional(readOnly = true)
    public int price(Plan plan, boolean yearly) {
        if (plan == null || plan == Plan.FREE) return 0;
        PlanPricing row = pricing.findByPlan(plan).orElse(null);
        if (row == null) return plan.price(yearly);
        return yearly ? row.getYearlyRupees() : row.getMonthlyRupees();
    }

    @Transactional(readOnly = true)
    public List<PlanPricing> listPaid() {
        return pricing.findAll().stream().filter(p -> p.getPlan() != Plan.FREE)
            .sorted((a, b) -> Integer.compare(a.getPlan().ordinal(), b.getPlan().ordinal())).toList();
    }

    @Transactional
    public PlanPricing update(Plan plan, int monthly, int yearly) {
        if (plan == null || plan == Plan.FREE)
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Only paid plans can be priced.");
        if (monthly < 1 || monthly > 10000000 || yearly < 1 || yearly > 10000000)
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Monthly and yearly prices must be between ₹1 and ₹1,00,00,000.");
        PlanPricing row = pricing.findByPlan(plan).orElseGet(() -> {
            PlanPricing created = new PlanPricing();
            created.setPlan(plan);
            return created;
        });
        row.setMonthlyRupees(monthly);
        row.setYearlyRupees(yearly);
        return pricing.save(row);
    }
}
