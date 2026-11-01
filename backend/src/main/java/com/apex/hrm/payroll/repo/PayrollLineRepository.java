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

public interface PayrollLineRepository extends JpaRepository<PayrollLine, UUID> {

    List<PayrollLine> findByRunId(UUID runId);

    List<PayrollLine> findByPayrollEmployeeId(UUID payrollEmployeeId);

    void deleteByRunId(UUID runId);

    /** Only immutable payroll runs qualify for annual tax reconciliation. */
    @Query("select l from PayrollLine l, PayrollRun r, PayPeriod p " +
           "where l.runId = r.id and r.payPeriodId = p.id " +
           "and l.employeeId = :employeeId and r.status in ('LOCKED', 'PAID') " +
           "and p.endDate between :startDate and :endDate")
    List<PayrollLine> lockedLinesForTaxYear(@Param("employeeId") UUID employeeId,
            @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("select coalesce(sum(case when l.category = 'EARNING' then l.amount else -l.amount end), 0) "
         + "from PayrollLine l, PayrollRun r, PayPeriod p "
         + "where l.runId = r.id and r.payPeriodId = p.id and l.employeeId = :e "
         + "and l.itemCode in ('BASIC', 'ABSENCE', 'LATE_UT', 'PRORATION') and r.runType = 'REGULAR' "
         + "and r.status in ('LOCKED', 'PAID') and p.endDate between :from and :to")
    BigDecimal basicEarned(@Param("e") UUID employeeId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
