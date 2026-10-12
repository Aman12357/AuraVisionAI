package com.auravision.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "app_users", uniqueConstraints = {
    @UniqueConstraint(name = "uk_app_users_email", columnNames = "email"),
    @UniqueConstraint(name = "uk_app_users_owner_token", columnNames = "owner_token")
})
@Getter @Setter @NoArgsConstructor
public class AccountUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 254)
    private String email;
    @Column(nullable = false, length = 512)
    private String passwordHash;
    @Column(name = "owner_token", nullable = false, length = 36)
    private String ownerToken;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); }
}
