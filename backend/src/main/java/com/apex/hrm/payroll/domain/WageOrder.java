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
@Table(name = "wage_order")
@Getter
@Setter
public class WageOrder extends GlobalEntity {
    private String regionCode;
    private String sector;
    private BigDecimal dailyRate;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String reference;
}
