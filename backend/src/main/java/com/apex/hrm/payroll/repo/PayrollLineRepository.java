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

    @Query("select coalesce(sum(case when l.itemCode = 'WTAX' then l.amount else -l.amount end), 0) "
         + "from PayrollLine l, PayrollRun r, PayPeriod p "
         + "where l.runId = r.id and r.payPeriodId = p.id and l.employeeId = :e and l.itemCode in ('WTAX', 'TAX_REFUND') "
         + "and r.status in ('LOCKED', 'PAID') and p.endDate between :from and :to "
         + "and r.tenantId = ?#{T(com.apex.hrm.common.TenantContext).get()}")
    BigDecimal taxWithheldYtd(@Param("e") UUID employeeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select coalesce(sum(l.amount), 0) from PayrollLine l, PayrollRun r, PayPeriod p "
         + "where l.runId = r.id and r.payPeriodId = p.id and l.employeeId = :e and l.itemCode = 'THIRTEENTH_MONTH' "
         + "and r.status in ('LOCKED', 'PAID') and p.endDate between :from and :to "
         + "and r.tenantId = ?#{T(com.apex.hrm.common.TenantContext).get()}")
    BigDecimal thirteenthPaid(@Param("e") UUID employeeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select coalesce(sum(l.amount), 0) from PayrollLine l, PayrollRun r, PayPeriod p "
         + "where l.runId = r.id and r.payPeriodId = p.id and l.employeeId = :e and l.itemCode = 'THIRTEENTH_MONTH' "
         + "and l.taxable = false and r.status in ('LOCKED', 'PAID') and p.endDate between :from and :to "
         + "and r.tenantId = ?#{T(com.apex.hrm.common.TenantContext).get()}")
    BigDecimal thirteenthExemptPaid(@Param("e") UUID employeeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select coalesce(sum(l.amount), 0) from PayrollLine l, PayrollRun r, PayPeriod p "
         + "where l.runId = r.id and r.payPeriodId = p.id and l.employeeId = :e and l.category = 'EARNING' "
         + "and l.taxable = false and l.itemCode not in ('THIRTEENTH_MONTH', 'DE_MINIMIS', 'TAX_REFUND') "
         + "and r.status in ('LOCKED', 'PAID') and p.endDate between :from and :to "
         + "and r.tenantId = ?#{T(com.apex.hrm.common.TenantContext).get()} and r.runType in ('REGULAR','OFF_CYCLE','FINAL_PAY')")
    BigDecimal otherExemptBenefitsYtd(@Param("e") UUID employeeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select coalesce(sum(case when l.category = 'EARNING' then l.amount else -l.amount end), 0) "
         + "from PayrollLine l, PayrollRun r, PayPeriod p "
         + "where l.runId = r.id and r.payPeriodId = p.id and l.employeeId = :e "
         + "and l.itemCode in ('BASIC', 'ABSENCE', 'LATE_UT') and r.runType = 'REGULAR' "
         + "and r.status in ('LOCKED', 'PAID') and p.endDate between :from and :to "
         + "and r.tenantId = ?#{T(com.apex.hrm.common.TenantContext).get()}")
    BigDecimal basicEarned(@Param("e") UUID employeeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select coalesce(sum(case when l.category = 'EARNING' then l.amount else 0 end), 0) "
         + "from PayrollLine l, PayrollRun r, PayPeriod p "
         + "where l.runId = r.id and r.payPeriodId = p.id and l.employeeId = :e "
         + "and l.itemCode not in ('TAX_REFUND') and r.status in ('LOCKED', 'PAID') "
         + "and p.endDate between :from and :to and r.tenantId = ?#{T(com.apex.hrm.common.TenantContext).get()}")
    BigDecimal grossCompensationYtd(@Param("e") UUID employeeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select coalesce(sum(case when l.category = 'EARNING' and l.taxable = true then l.amount else 0 end), 0) "
         + "from PayrollLine l, PayrollRun r, PayPeriod p "
         + "where l.runId = r.id and r.payPeriodId = p.id and l.employeeId = :e "
         + "and l.itemCode not in ('TAX_REFUND') and r.status in ('LOCKED', 'PAID') "
         + "and p.endDate between :from and :to and r.tenantId = ?#{T(com.apex.hrm.common.TenantContext).get()}")
    BigDecimal taxableCompensationYtd(@Param("e") UUID employeeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select coalesce(sum(case when l.category = 'EARNING' and l.taxable = false then l.amount else 0 end), 0) "
         + "from PayrollLine l, PayrollRun r, PayPeriod p "
         + "where l.runId = r.id and r.payPeriodId = p.id and l.employeeId = :e "
         + "and l.itemCode not in ('TAX_REFUND') and r.status in ('LOCKED', 'PAID') "
         + "and p.endDate between :from and :to and r.tenantId = ?#{T(com.apex.hrm.common.TenantContext).get()}")
    BigDecimal nonTaxableCompensationYtd(@Param("e") UUID employeeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Employee or employer statutory contributions already recorded in locked regular runs overlapping a month. */
    @Query("select coalesce(sum(l.amount), 0) from PayrollLine l, PayrollRun r, PayPeriod p "
         + "where l.runId = r.id and r.payPeriodId = p.id and l.employeeId = :e and l.itemCode = :code "
         + "and r.runType = 'REGULAR' and r.status in ('LOCKED', 'PAID') "
         + "and p.startDate <= :to and p.endDate >= :from "
         + "and r.tenantId = ?#{T(com.apex.hrm.common.TenantContext).get()}")
    BigDecimal lockedRegularStatutoryAmount(@Param("e") UUID employeeId, @Param("code") String code,
                                             @Param("from") LocalDate from, @Param("to") LocalDate to);
}
