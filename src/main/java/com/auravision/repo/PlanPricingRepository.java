package com.auravision.repo;

import com.auravision.model.Plan;
import com.auravision.model.PlanPricing;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PlanPricingRepository extends JpaRepository<PlanPricing, Long> {
    Optional<PlanPricing> findByPlan(Plan plan);
}
