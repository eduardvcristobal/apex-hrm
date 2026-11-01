package com.apex.hrm.personnel.domain;

import com.apex.hrm.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "employee_bank_account")
@Getter
@Setter
public class EmployeeBankAccount extends BaseEntity {
    private UUID employeeId;
    private String bankCode;
    @JsonIgnore
    @Column(name = "account_no_ciphertext", columnDefinition = "text")
    private String accountNoCiphertext;
    private String accountName;
    private Boolean primaryAccount = true;
}
