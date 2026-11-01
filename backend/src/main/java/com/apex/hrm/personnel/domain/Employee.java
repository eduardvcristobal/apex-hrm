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
@Table(name = "employee")
@Getter
@Setter
public class Employee extends BaseEntity {
    private UUID companyId;
    private String employeeNo;
    private String firstName;
    private String middleName;
    private String lastName;
    private String suffix;
    private LocalDate birthDate;
    private String sex;
    private String civilStatus;
    private String email;
    private String mobile;
    private String address;
    private String tin;
    private String sssNo;
    private String philhealthNo;
    private String pagibigNo;
    private LocalDate hireDate;
    private LocalDate separationDate;
    private Boolean minimumWageEarner = false;
    private String status = "ACTIVE";
}
