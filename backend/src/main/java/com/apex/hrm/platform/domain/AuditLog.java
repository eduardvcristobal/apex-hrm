package com.apex.hrm.platform.domain;

import com.apex.hrm.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
public class AuditLog extends BaseEntity {
    private String entityType;
    private UUID entityId;
    private String action;
    private String actor;
    private String summary;
}
