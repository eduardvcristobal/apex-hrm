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
@Table(name = "payslip")
@Getter
@Setter
public class Payslip extends BaseEntity {
    private UUID payrollEmployeeId;
    private UUID employeeId;
    private UUID runId;
    private Integer versionNo = 1;
    private String snapshot;
    private Instant issuedAt;
}
