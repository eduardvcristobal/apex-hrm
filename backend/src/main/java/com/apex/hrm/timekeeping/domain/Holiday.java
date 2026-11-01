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
@Table(name = "holiday")
@Getter
@Setter
public class Holiday extends BaseEntity {
    private LocalDate holidayDate;
    private String name;
    private String holidayType;
    private UUID workLocationId;
}
