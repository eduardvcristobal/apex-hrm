package com.apex.hrm.payroll.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.org.repo.WorkLocationRepository;
import com.apex.hrm.payroll.domain.*;
import com.apex.hrm.payroll.repo.*;
import com.apex.hrm.personnel.repo.*;
import com.apex.hrm.platform.service.AuditService;
import com.apex.hrm.timekeeping.domain.DtrDay;
import com.apex.hrm.timekeeping.repo.DtrDayRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M3.2.4: lifecycle changes freeze DTR and produce payslip snapshots before payment marking. */
class PayrollLifecycleCertificationTest {
    private final PayrollRunRepository runs = mock(PayrollRunRepository.class);
    private final PayPeriodRepository periods = mock(PayPeriodRepository.class);
    private final PayGroupRepository payGroups = mock(PayGroupRepository.class);
    private final PayrollEmployeeRepository payrollEmployees = mock(PayrollEmployeeRepository.class);
    private final PayrollLineRepository lines = mock(PayrollLineRepository.class);
    private final AdjustmentRepository adjustments = mock(AdjustmentRepository.class);
    private final EmploymentRecordRepository employment = mock(EmploymentRecordRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final CompensationRepository compensations = mock(CompensationRepository.class);
    private final DtrDayRepository dtrDays = mock(DtrDayRepository.class);
    private final RecurringItemRepository recurring = mock(RecurringItemRepository.class);
    private final LoanRepository loans = mock(LoanRepository.class);
    private final PremiumRuleRepository premiumRules = mock(PremiumRuleRepository.class);
    private final StatutoryContributionRuleRepository statutory = mock(StatutoryContributionRuleRepository.class);
    private final WithholdingTaxBracketRepository taxBrackets = mock(WithholdingTaxBracketRepository.class);
    private final WageOrderRepository wageOrders = mock(WageOrderRepository.class);
    private final WorkLocationRepository locations = mock(WorkLocationRepository.class);
    private final PayslipService payslips = mock(PayslipService.class);
    private final AuditService audit = mock(AuditService.class);
    private final UUID runId = UUID.randomUUID();
    private final UUID periodId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();

    @Test
    void reviewedRunApprovesLocksIssuesPayslipAndMarksPaid() {
        PayrollRun run = new PayrollRun();
        run.setId(runId);
        run.setPayPeriodId(periodId);
        run.setRunType("REGULAR");
        run.setStatus("COMPUTED");
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(runs.save(any(PayrollRun.class))).thenAnswer(inv -> inv.getArgument(0));
        PayPeriod period = new PayPeriod();
        period.setStartDate(LocalDate.of(2026, 10, 1));
        period.setEndDate(LocalDate.of(2026, 10, 15));
        period.setPayDate(LocalDate.of(2026, 10, 20));
        when(periods.findById(periodId)).thenReturn(Optional.of(period));
        PayrollEmployee pe = new PayrollEmployee();
        pe.setId(UUID.randomUUID());
        pe.setEmployeeId(employeeId);
        pe.setNetPay(new BigDecimal("12500.00"));
        when(payrollEmployees.findByRunId(runId)).thenReturn(List.of(pe));
        when(lines.findByRunId(runId)).thenReturn(List.of());
        DtrDay dtr = new DtrDay();
        dtr.setStatus("OPEN");
        when(dtrDays.findByEmployeeIdAndWorkDateBetween(employeeId, period.getStartDate(), period.getEndDate()))
                .thenReturn(List.of(dtr));

        PayrollService service = service();
        assertEquals("REVIEWED", service.review(runId).getStatus());
        UUID approver = UUID.randomUUID();
        assertEquals("APPROVED", service.approve(runId, approver).getStatus());
        assertEquals(approver, run.getApprovedBy());
        assertNotNull(run.getApprovedAt());
        assertEquals("LOCKED", service.lock(runId).getStatus());
        verify(payslips).issue(eq(run), eq(period), eq(pe), eq(List.of()));
        assertEquals("LOCKED", dtr.getStatus());
        assertEquals("LOCKED", period.getStatus());
        assertNotNull(run.getLockedAt());
        assertEquals("PAID", service.markPaid(runId).getStatus());
        assertNotNull(run.getPaidAt());
        verify(runs, times(4)).save(run);
    }

    @Test
    void paymentCannotSkipApprovalAndLocking() {
        PayrollRun run = new PayrollRun();
        run.setStatus("COMPUTED");
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        assertThrows(BusinessException.class, () -> service().markPaid(runId));
        assertThrows(BusinessException.class, () -> service().lock(runId));
        verify(runs, never()).save(any());
        verifyNoInteractions(payslips);
    }

    private PayrollService service() {
        return new PayrollService(runs, periods, payGroups, payrollEmployees, lines, adjustments,
                employment, employees, compensations, dtrDays, recurring, loans, premiumRules,
                statutory, taxBrackets, wageOrders, locations, payslips, audit);
    }
}
