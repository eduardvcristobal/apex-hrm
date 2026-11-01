package com.apex.hrm.payroll.domain;

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
@Table(name = "gl_mapping")
@Getter
@Setter
public class GlMapping extends BaseEntity {
    private String itemCode;
    private UUID debitAccountId;
    private UUID creditAccountId;
}
