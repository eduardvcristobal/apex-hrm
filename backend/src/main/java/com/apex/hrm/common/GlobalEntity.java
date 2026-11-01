package com.apex.hrm.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Base for global (non tenant-scoped) reference data: statutory rates, tax tables, premium rules, wage orders. */
@MappedSuperclass
@Getter
@Setter
@JsonIgnoreProperties(value = {"id", "createdAt", "updatedAt"}, allowGetters = true)
public abstract class GlobalEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
