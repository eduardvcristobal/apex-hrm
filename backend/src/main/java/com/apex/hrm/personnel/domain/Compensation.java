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
@Table(name = "compensation")
@Getter
@Setter
public class Compensation extends BaseEntity {
    private UUID employeeId;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String payBasis;
    private BigDecimal baseRate;
    private String reason;
}
