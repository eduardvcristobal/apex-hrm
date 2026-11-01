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
@Table(name = "payroll_run")
@Getter
@Setter
public class PayrollRun extends BaseEntity {
    private UUID payPeriodId;
    private UUID payGroupId;
    private String runType = "REGULAR";
    private String status = "DRAFT";
    private Instant computedAt;
    private UUID approvedBy;
    private Instant approvedAt;
    private Instant lockedAt;
    private Instant paidAt;
    private String remarks;
    private UUID employeeId;
    private Integer employeeCount = 0;
    private BigDecimal totalGross = new BigDecimal("0");
    private BigDecimal totalDeductions = new BigDecimal("0");
    private BigDecimal totalNet = new BigDecimal("0");
    private BigDecimal totalEmployerCost = new BigDecimal("0");
}
