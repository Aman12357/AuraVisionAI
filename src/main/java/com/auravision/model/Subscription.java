package com.auravision.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "subscriptions")
@Getter @Setter @NoArgsConstructor
public class Subscription {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 40)
    private String ownerToken;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Plan plan;
    @Column(length = 254)
    private String email;
    @Column(length = 40)
    private String razorpaySubscriptionId;
    // Provider lifecycle state, e.g. ACTIVE, PENDING, CANCEL_AT_CYCLE_END, CANCELLED, HALTED.
    @Column(length = 32)
    private String status;
    private Instant expiresAt;
}
