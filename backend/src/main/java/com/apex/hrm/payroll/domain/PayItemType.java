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
@Table(name = "pay_item_type")
@Getter
@Setter
public class PayItemType extends BaseEntity {
    private String code;
    private String name;
    private String category;
    private Boolean taxable = true;
    private Boolean systemItem = false;
}
