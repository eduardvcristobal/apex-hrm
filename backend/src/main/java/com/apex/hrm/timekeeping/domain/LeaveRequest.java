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
@Table(name = "leave_request")
@Getter
@Setter
public class LeaveRequest extends BaseEntity {
    private UUID employeeId;
    private UUID leaveTypeId;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private BigDecimal days;
    private Boolean halfDay = false;
    private String reason;
    private String status = "PENDING";
    private UUID approvedBy;
    private Instant approvedAt;
}
