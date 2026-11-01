package com.apex.hrm.payroll.domain;

import com.apex.hrm.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name="bank_credit_import")
@Getter @Setter
public class BankCreditImport extends BaseEntity {
    private UUID bankFileId;
    private String evidenceReference;
    private UUID recordedBy;
}
