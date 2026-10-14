package com.auravision.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "plan_pricing", uniqueConstraints = @UniqueConstraint(name = "uk_plan_pricing_plan", columnNames = "plan"))
@Getter @Setter @NoArgsConstructor
public class PlanPricing {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, unique = true, length = 24)
    private Plan plan;
    @Column(nullable = false)
    private int monthlyRupees;
    @Column(nullable = false)
    private int yearlyRupees;
    @Column(nullable = false)
    private Instant updatedAt;
    @PrePersist @PreUpdate void touch() { updatedAt = Instant.now(); }
}
