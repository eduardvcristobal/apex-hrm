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
@Table(name = "bank_file")
@Getter
@Setter
public class BankFile extends BaseEntity {
    private UUID runId;
    private UUID bankTemplateId;
    private String fileName;
    private String content;
    private Integer recordCount = 0;
    private BigDecimal totalAmount = new BigDecimal("0");
}
