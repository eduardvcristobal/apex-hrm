package com.apex.hrm.payroll.domain;

import com.apex.hrm.common.GlobalEntity;
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
@Table(name = "statutory_contribution_rule")
@Getter
@Setter
public class StatutoryContributionRule extends GlobalEntity {
    private String agency;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private BigDecimal employeeRate;
    private BigDecimal employerRate;
    private BigDecimal minBase;
    private BigDecimal maxBase;
    private BigDecimal mscStep;
    private BigDecimal ecLow;
    private BigDecimal ecHigh;
    private BigDecimal ecThreshold;
    private BigDecimal mpfThreshold;
    private BigDecimal lowIncomeThreshold;
    private BigDecimal lowIncomeEmployeeRate;
}
