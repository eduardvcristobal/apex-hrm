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
@Table(name = "dtr_day")
@Getter
@Setter
public class DtrDay extends BaseEntity {
    private UUID employeeId;
    private LocalDate workDate;
    private UUID scheduleId;
    private String dayType;
    private Instant firstIn;
    private Instant lastOut;
    private Integer regularMinutes = 0;
    private Integer lateMinutes = 0;
    private Integer undertimeMinutes = 0;
    private Integer nightMinutes = 0;
    private Integer nightOtMinutes = 0;
    private Integer otDetectedMinutes = 0;
    private Integer otApprovedMinutes = 0;
    private Boolean absent = false;
    private Boolean paidLeave = false;
    private String status = "OPEN";
    private Boolean holidayPayEligible = true;
}
