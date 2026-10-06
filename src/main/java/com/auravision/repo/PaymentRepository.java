package com.auravision.repo;

import com.auravision.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderIdAndOwnerToken(String orderId, String ownerToken);
}
