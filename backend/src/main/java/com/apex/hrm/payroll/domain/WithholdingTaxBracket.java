package com.apex.hrm.payroll.domain;

import com.apex.hrm.common.GlobalEntity;
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
@Table(name = "withholding_tax_bracket")
@Getter
@Setter
public class WithholdingTaxBracket extends GlobalEntity {
    private LocalDate effectiveFrom;
    private String periodType;
    private BigDecimal lowerBound;
    private BigDecimal upperBound;
    private BigDecimal baseTax;
    private BigDecimal rate;
}
