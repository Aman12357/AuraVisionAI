package com.auravision.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "razorpay_plans", uniqueConstraints = @UniqueConstraint(
    name = "uk_razorpay_plan_billing", columnNames = {"plan", "yearly"}))
@Getter @Setter @NoArgsConstructor
public class RazorpayPlanMapping {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    private Plan plan;
    @Column(nullable = false)
    private boolean yearly;
    @Column(nullable = false, unique = true, length = 40)
    private String razorpayPlanId;
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int amountRupees;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); }
}
