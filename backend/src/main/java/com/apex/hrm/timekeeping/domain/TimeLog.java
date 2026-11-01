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
@Table(name = "time_log")
@Getter
@Setter
public class TimeLog extends BaseEntity {
    private UUID employeeId;
    private Instant logTime;
    private String logType;
    private String source;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String deviceId;
    private String photoRef;
    private String status = "VALID";
    private String remarks;
}
