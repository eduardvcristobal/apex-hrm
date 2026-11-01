package com.apex.hrm.payroll.domain;

import com.apex.hrm.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name="bank_credit_result")
@Getter @Setter
public class BankCreditResult extends BaseEntity {
    private UUID bankImportId;
    private UUID runId;
    private UUID bankFileId;
    private UUID employeeId;
    private String employeeNo;
    private BigDecimal amount;
    private String creditStatus;
    private String bankReference;
}
