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
@Table(name = "premium_rule")
@Getter
@Setter
public class PremiumRule extends GlobalEntity {
    private String dayType;
    private BigDecimal workedMultiplier;
    private BigDecimal otMultiplier;
    private BigDecimal unworkedMultiplier;
    private Boolean baseIncludedMonthly;
    private BigDecimal nightDiffRate;
    private LocalDate effectiveFrom;
}
