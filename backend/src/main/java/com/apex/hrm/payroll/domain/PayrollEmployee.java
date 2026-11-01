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
@Table(name = "payroll_employee")
@Getter
@Setter
public class PayrollEmployee extends BaseEntity {
    private UUID runId;
    private UUID employeeId;
    private UUID costCenterId;
    private BigDecimal gross = new BigDecimal("0");
    private BigDecimal taxableIncome = new BigDecimal("0");
    private BigDecimal totalDeductions = new BigDecimal("0");
    private BigDecimal netPay = new BigDecimal("0");
    private BigDecimal employerCost = new BigDecimal("0");
    private String warnings;
}
