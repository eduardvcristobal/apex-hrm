package com.apex.hrm.org.domain;

import com.apex.hrm.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "work_location")
@Getter
@Setter
public class WorkLocation extends BaseEntity {
    private UUID companyId;
    private String code;
    private String name;
    private String regionCode;
    private String city;
    private String address;
    private BigDecimal latitude;
    private BigDecimal longitude;
    @Column(name = "geofence_radius_m")
    private Integer geofenceRadiusM;
}
