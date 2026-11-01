package com.apex.hrm.payroll.domain;

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
@Table(name = "adjustment")
@Getter
@Setter
public class Adjustment extends BaseEntity {
    private UUID employeeId;
    private String itemCode;
    private String description;
    private String category;
    private BigDecimal amount;
    private Boolean taxable = true;
    private String reason;
    private UUID sourcePeriodId;
    private UUID targetPeriodId;
    private String status = "PENDING";
    private UUID appliedRunId;
    private UUID requestedBy;
    private UUID approvedBy;
    private Instant approvedAt;
}
