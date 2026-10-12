package com.auravision.repo;

import com.auravision.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderIdAndOwnerToken(String orderId, String ownerToken);
    Optional<Payment> findByGatewayPaymentId(String gatewayPaymentId);
    Optional<Payment> findFirstByRazorpaySubscriptionIdOrderByCreatedAtAsc(String razorpaySubscriptionId);
    List<Payment> findTop20ByOwnerTokenOrderByCreatedAtDesc(String ownerToken);
}
