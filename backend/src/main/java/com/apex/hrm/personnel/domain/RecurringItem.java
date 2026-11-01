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
@Table(name = "recurring_item")
@Getter
@Setter
public class RecurringItem extends BaseEntity {
    private UUID employeeId;
    private String itemCode;
    private String category;
    private String description;
    private BigDecimal amount;
    private Boolean taxable = true;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
}
