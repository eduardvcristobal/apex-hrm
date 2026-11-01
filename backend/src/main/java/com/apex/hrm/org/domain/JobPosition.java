package com.apex.hrm.org.domain;

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
@Table(name = "job_position")
@Getter
@Setter
public class JobPosition extends BaseEntity {
    private String code;
    private String title;
    private String grade;
}
