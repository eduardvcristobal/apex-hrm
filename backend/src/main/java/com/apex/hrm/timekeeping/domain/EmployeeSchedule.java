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
@Table(name = "employee_schedule")
@Getter
@Setter
public class EmployeeSchedule extends BaseEntity {
    private UUID employeeId;
    private UUID scheduleId;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
}
