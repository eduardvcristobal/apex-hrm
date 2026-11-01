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
@Table(name = "attendance_correction")
@Getter
@Setter
public class AttendanceCorrection extends BaseEntity {
    private UUID employeeId;
    private LocalDate workDate;
    private Instant requestedIn;
    private Instant requestedOut;
    private String reason;
    private String status = "PENDING";
    private UUID approvedBy;
    private Instant approvedAt;
}
