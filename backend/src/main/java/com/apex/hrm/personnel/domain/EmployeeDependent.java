package com.apex.hrm.personnel.domain;

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
@Table(name = "employee_dependent")
@Getter
@Setter
public class EmployeeDependent extends BaseEntity {
    private UUID employeeId;
    private String fullName;
    private String relationship;
    private LocalDate birthDate;
}
