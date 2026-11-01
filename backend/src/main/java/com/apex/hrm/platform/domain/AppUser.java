package com.apex.hrm.platform.domain;

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
@Table(name = "app_user")
@Getter
@Setter
public class AppUser extends BaseEntity {
    private String username;
    private String email;
    private String passwordHash;
    private UUID employeeId;
    private String role = "EMPLOYEE";
    private String status = "ACTIVE";
}
