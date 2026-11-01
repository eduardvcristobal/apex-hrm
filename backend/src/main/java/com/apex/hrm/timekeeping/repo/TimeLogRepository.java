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

public interface TimeLogRepository extends JpaRepository<TimeLog, UUID> {

    List<TimeLog> findByEmployeeIdAndLogTimeBetweenAndStatusOrderByLogTime(UUID employeeId, Instant from, Instant to, String status);

    Optional<TimeLog> findFirstByEmployeeIdOrderByLogTimeDesc(UUID employeeId);
}
