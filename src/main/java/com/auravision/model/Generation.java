package com.auravision.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "generations", indexes = {@Index(columnList = "createdAt"), @Index(columnList = "ownerToken")})
@Getter @Setter @NoArgsConstructor
public class Generation {
    public enum Type { IMAGE, VIDEO }
    public enum Status { PENDING, RUNNING, DONE, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Type type;

    @Column(nullable = false, length = 2000)
    private String prompt;

    private String aspectRatio;
    private String model;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Status status = Status.PENDING;

    private String operationName;   // Veo long-running operation id
    private String mimeType;
    private String filePath;        // where the result is stored on disk

    @Column(length = 40)
    private String ownerToken;      // anonymous visitor id (cookie), keeps each gallery private

    @Column(length = 1000)
    private String error;

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate  void onUpdate() { updatedAt = Instant.now(); }
}
