package com.apex.hrm.payroll.domain;

import com.apex.hrm.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Human-verified bank acknowledgment; not an automatic confirmation from a banking API. */
@Entity
@Table(name = "payroll_bank_settlement")
@Getter
@Setter
public class PayrollBankSettlement extends BaseEntity {
    private UUID runId;
    private UUID bankFileId;
    private String bankReference;
    private BigDecimal acknowledgedAmount;
    private String evidenceReference;
    private String status = "RECORDED";
    private UUID recordedBy;
    private UUID confirmedBy;
    private Instant confirmedAt;
    private String exceptionReason;
    private String exceptionEvidenceReference;
    private UUID exceptionBy;
    private Instant exceptionAt;
}
