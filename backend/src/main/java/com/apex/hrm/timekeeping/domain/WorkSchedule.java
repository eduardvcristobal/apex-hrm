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
@Table(name = "work_schedule")
@Getter
@Setter
public class WorkSchedule extends BaseEntity {
    private String code;
    private String name;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer breakMinutes = 60;
    private Integer graceMinutes = 0;
    private String restDays = "SAT,SUN";
}
