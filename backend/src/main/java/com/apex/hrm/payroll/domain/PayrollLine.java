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
@Table(name = "payroll_line")
@Getter
@Setter
public class PayrollLine extends BaseEntity {
    private UUID runId;
    private UUID payrollEmployeeId;
    private UUID employeeId;
    private String itemCode;
    private String description;
    private String category;
    private BigDecimal quantity;
    private BigDecimal rate;
    private BigDecimal amount;
    private Boolean taxable = true;
    private String sourceType;
    private UUID sourceId;
    private UUID costCenterId;
}
