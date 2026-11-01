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
@Table(name = "pay_group")
@Getter
@Setter
public class PayGroup extends BaseEntity {
    private UUID companyId;
    private String code;
    private String name;
    private String frequency = "SEMI_MONTHLY";
    private BigDecimal daysFactor = new BigDecimal("261");
    private BigDecimal hoursPerDay = new BigDecimal("8");
    private String contributionSchedule = "LAST_CUTOFF";
    private String taxMethod = "STRICT";
}
