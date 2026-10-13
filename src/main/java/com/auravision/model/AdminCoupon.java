package com.auravision.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "admin_coupons", uniqueConstraints = @UniqueConstraint(name = "uk_admin_coupon_code", columnNames = "code"))
@Getter @Setter @NoArgsConstructor
public class AdminCoupon {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 40)
    private String code;
    @Column(nullable = false)
    private int discountPercent;
    @Column(nullable = false)
    private boolean active = true;
    private Integer maxRedemptions;
    @Column(nullable = false)
    private int redeemedCount = 0;
    private Instant expiresAt;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); }
}
