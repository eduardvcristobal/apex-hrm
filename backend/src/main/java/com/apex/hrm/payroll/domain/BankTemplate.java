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
@Table(name = "bank_template")
@Getter
@Setter
public class BankTemplate extends BaseEntity {
    private String bankCode;
    private String name;
    private String delimiter = ",";
    private Boolean includeHeader = true;
    private String columnSpec = "ACCOUNT_NO,ACCOUNT_NAME,AMOUNT";
}
