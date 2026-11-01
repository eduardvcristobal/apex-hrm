package com.apex.hrm.timekeeping.domain;

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
@Table(name = "leave_type")
@Getter
@Setter
public class LeaveType extends BaseEntity {
    private String code;
    private String name;
    private Boolean paid = true;
    private BigDecimal annualEntitlement;
    private Boolean convertible = false;
    private BigDecimal carryOverMax;
}
