package com.apex.hrm.personnel.domain;

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
@Table(name = "loan")
@Getter
@Setter
public class Loan extends BaseEntity {
    private UUID employeeId;
    private String loanType;
    private String referenceNo;
    private BigDecimal principal;
    private BigDecimal amortization;
    private BigDecimal balance;
    private LocalDate startDate;
    private String status = "ACTIVE";
}
