package com.auravision.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "payments", indexes = @Index(columnList = "ownerToken"))
@Getter @Setter @NoArgsConstructor
public class Payment {
    public enum Status { CREATED, PENDING, PAID, FAILED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 40)
    private String orderId;
    @Column(nullable = false, length = 40)
    private String ownerToken;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Plan plan;
    private boolean yearly;
    private int amountRupees;
    @Column(length = 254)
    private String email;
    @Column(length = 32)
    private String method;
    @Column(length = 80)
    private String txnRef;
    @Column(length = 40)
    private String razorpaySubscriptionId;
    @Column(length = 40)
    private String razorpayPlanId;
    @Column(length = 40)
    private String checkoutPaymentId;
    @Column(unique = true, length = 40)
    private String gatewayPaymentId;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Status status = Status.CREATED;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    private Instant paidAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); }
}
