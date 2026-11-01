package com.apex.hrm.payroll.domain;

import com.apex.hrm.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Immutable financial snapshot with separately approved lifecycle status. */
@Entity
@Table(name = "annual_tax_adjustment_request")
@Getter
@Setter
public class AnnualTaxAdjustmentRequest extends BaseEntity {
    private UUID employeeId;
    private Integer taxYear;
    private UUID targetPeriodId;
    private UUID requestedBy;
    private UUID approvedBy;
    private Instant approvedAt;
    private UUID adjustmentId;
    private String status = "PENDING";
    private BigDecimal reconciliationTax;
    private BigDecimal reconciliationWithheld;
    private BigDecimal adjustmentAmount;
    private String justification;
    private Boolean attestedCompleteYear;
    private Boolean attestedBenefitsReviewed;
}
