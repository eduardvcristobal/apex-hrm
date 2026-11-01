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
@Table(name = "journal_entry")
@Getter
@Setter
public class JournalEntry extends BaseEntity {
    private UUID runId;
    private LocalDate entryDate;
    private String description;
    private String status = "DRAFT";
    private BigDecimal totalDebit = new BigDecimal("0");
    private BigDecimal totalCredit = new BigDecimal("0");
}
