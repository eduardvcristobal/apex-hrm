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
    private String employeeRdoCode;
    private String registeredZipCode;
    private String localHomeAddress;
    private String localHomeZipCode;
    private String foreignAddress;
    private BigDecimal statutoryMinimumWageDaily;
    private BigDecimal statutoryMinimumWageMonthly;
    private String tin;
    private String branchCode;
    private String regionNum;
    private String nationality;
    private String birEmploymentStatus;
    private String birReasonSeparation;
    private String birSubstitutedFiling;
    private Integer minimumWageFactorUsed;
    private BigDecimal priorNontaxBasicSmw = BigDecimal.ZERO;
    private BigDecimal priorNontaxHolidayPay = BigDecimal.ZERO;
    private BigDecimal priorNontaxOvertimePay = BigDecimal.ZERO;
    private BigDecimal priorNontaxNightDiff = BigDecimal.ZERO;
    private BigDecimal priorNontaxHazardPay = BigDecimal.ZERO;
    private BigDecimal priorNontax13thMonth = BigDecimal.ZERO;
    private BigDecimal priorNontaxDeminimis = BigDecimal.ZERO;
    private BigDecimal priorNontaxSssEtc = BigDecimal.ZERO;
    private BigDecimal priorNontaxSalaries = BigDecimal.ZERO;
    private BigDecimal priorTaxableBasicSalary = BigDecimal.ZERO;
    private BigDecimal priorTaxable13thMonth = BigDecimal.ZERO;
    private BigDecimal priorTaxableSalaries = BigDecimal.ZERO;
    private String sssNo;
    private String philhealthNo;
    private String pagibigNo;
    private LocalDate hireDate;
    private LocalDate separationDate;
    private Boolean minimumWageEarner = false;
    private String priorEmployerName;
    private String priorEmployerTin;
    private String priorEmployerAddress;
    private String priorEmployerRdoCode;
    private String priorEmployerZipCode;
    private BigDecimal priorEmployerCompensation = BigDecimal.ZERO;
    private BigDecimal priorEmployerTaxableCompensation = BigDecimal.ZERO;
    private BigDecimal priorEmployerNonTaxableCompensation = BigDecimal.ZERO;
    private BigDecimal priorEmployerTaxWithheld = BigDecimal.ZERO;
    private Boolean priorEmployerCertificateReceived = false;
    private String status = "ACTIVE";
}
