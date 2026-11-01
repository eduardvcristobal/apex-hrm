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
@Table(name = "journal_entry_line")
@Getter
@Setter
public class JournalEntryLine extends BaseEntity {
    private UUID journalEntryId;
    private UUID accountId;
    private UUID costCenterId;
    private BigDecimal debit = new BigDecimal("0");
    private BigDecimal credit = new BigDecimal("0");
    private String memo;
}
