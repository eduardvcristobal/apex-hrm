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
@Table(name = "leave_ledger")
@Getter
@Setter
public class LeaveLedger extends BaseEntity {
    private UUID employeeId;
    private UUID leaveTypeId;
    private LocalDate entryDate;
    private String entryType;
    private BigDecimal days;
    private String referenceType;
    private UUID referenceId;
    private String remarks;
}
