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
@Table(name = "pay_period")
@Getter
@Setter
public class PayPeriod extends BaseEntity {
    private UUID payGroupId;
    private Integer periodOfMonth = 1;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate payDate;
    private String status = "OPEN";
}
