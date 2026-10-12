package com.auravision.repo;

import com.auravision.model.Plan;
import com.auravision.model.RazorpayPlanMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RazorpayPlanRepository extends JpaRepository<RazorpayPlanMapping, Long> {
    Optional<RazorpayPlanMapping> findByPlanAndYearly(Plan plan, boolean yearly);
}
