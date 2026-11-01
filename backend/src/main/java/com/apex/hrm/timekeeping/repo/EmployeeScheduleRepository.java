package com.apex.hrm.timekeeping.repo;

import com.apex.hrm.org.domain.*;
import com.apex.hrm.payroll.domain.*;
import com.apex.hrm.personnel.domain.*;
import com.apex.hrm.platform.domain.*;
import com.apex.hrm.timekeeping.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeScheduleRepository extends JpaRepository<EmployeeSchedule, UUID> {

    @Query("select s from EmployeeSchedule s where s.effectiveFrom <= :to and (s.effectiveTo is null or s.effectiveTo >= :from)")
    List<EmployeeSchedule> findOverlapping(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
