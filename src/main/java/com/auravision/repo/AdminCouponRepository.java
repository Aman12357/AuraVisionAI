package com.auravision.repo;

import com.auravision.model.AdminCoupon;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AdminCouponRepository extends JpaRepository<AdminCoupon, Long> {
    Optional<AdminCoupon> findByCodeIgnoreCase(String code);
}
