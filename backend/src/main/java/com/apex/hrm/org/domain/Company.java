package com.apex.hrm.org.domain;

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
@Table(name = "company")
@Getter
@Setter
public class Company extends BaseEntity {
    private String legalName;
    private String tradeName;
    private String tin;
    private String rdoCode;
    private String sssEmployerNo;
    private String philhealthEmployerNo;
    private String pagibigEmployerNo;
    private String address;
}
