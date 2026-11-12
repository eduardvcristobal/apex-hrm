package com.apex.hrm.payroll.domain;

import com.apex.hrm.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "employee_pera_tax_credit")
@Getter
@Setter
public class EmployeePeraTaxCredit extends BaseEntity {
    private UUID employeeId;
    private Integer taxYear;
    private BigDecimal qualifiedContributions = BigDecimal.ZERO;
    private Boolean overseasFilipino = false;
    private BigDecimal taxCreditAmount = BigDecimal.ZERO;
    private String certificateNumber;
    private Boolean certificateReceived = false;
}
