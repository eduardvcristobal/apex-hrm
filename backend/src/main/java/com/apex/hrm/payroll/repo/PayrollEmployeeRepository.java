package com.apex.hrm.payroll.repo;

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

public interface PayrollEmployeeRepository extends JpaRepository<PayrollEmployee, UUID> {

    List<PayrollEmployee> findByRunId(UUID runId);

    void deleteByRunId(UUID runId);

    @Query("select coalesce(sum(e.taxableIncome), 0) from PayrollEmployee e, PayrollRun r, PayPeriod p "
         + "where e.runId = r.id and r.payPeriodId = p.id and e.employeeId = :e and r.status in ('LOCKED', 'PAID') "
         + "and r.runType in ('REGULAR', 'THIRTEENTH_MONTH', 'OFF_CYCLE', 'FINAL_PAY') and p.endDate between :from and :to")
    BigDecimal taxableIncomeYtd(@Param("e") UUID employeeId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
