package com.apex.hrm.payroll.service;

import com.apex.hrm.payroll.domain.PayPeriod;
import com.apex.hrm.payroll.domain.PayrollRun;
import com.apex.hrm.payroll.repo.PayPeriodRepository;
import com.apex.hrm.payroll.repo.PayrollRunRepository;
import com.apex.hrm.platform.service.AuditService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies payroll service entry points acquire row locks before modifying
 * state. Real race timing is covered separately by PostgreSQL integration.
 */
@ExtendWith(MockitoExtension.class)
class PayrollLockingTest {
    @Mock private PayrollRunRepository runs;
    @Mock private PayPeriodRepository periods;
    @Mock private AuditService audit;
    @InjectMocks private PayrollService payroll;

    @Test
    void createRunLocksPeriodBeforeCheckingForDuplicate() {
        UUID periodId = UUID.randomUUID();
        PayPeriod period = new PayPeriod();
        period.setStartDate(LocalDate.of(2026, 10, 1));
        period.setEndDate(LocalDate.of(2026, 10, 15));
        period.setPayGroupId(UUID.randomUUID());
        when(periods.findLockedById(periodId)).thenReturn(Optional.of(period));
        when(runs.findByPayPeriodIdAndRunType(periodId, "REGULAR")).thenReturn(List.of());
        when(runs.save(any(PayrollRun.class))).thenAnswer(invocation -> {
            PayrollRun run = invocation.getArgument(0);
            return run;
        });
        PayrollRun created = payroll.createRun(periodId, "REGULAR");
        assertEquals("DRAFT", created.getStatus());
        verify(periods).findLockedById(periodId);
        verify(periods, never()).findById(periodId);
    }

    @Test
    void reviewLoadsRunUnderPessimisticLockBeforeStateChange() {
        UUID runId = UUID.randomUUID();
        PayrollRun run = new PayrollRun();
        run.setStatus("COMPUTED");
        when(runs.findLockedById(runId)).thenReturn(Optional.of(run));
        when(runs.save(run)).thenReturn(run);
        assertEquals("REVIEWED", payroll.review(runId).getStatus());
        verify(runs).findLockedById(runId);
        verify(runs, never()).findById(runId);
    }

    @Test
    void rejectedTransitionDoesNotPersistWhenLockedStateChanged() {
        UUID runId = UUID.randomUUID();
        PayrollRun run = new PayrollRun();
        run.setStatus("PAID");
        when(runs.findLockedById(runId)).thenReturn(Optional.of(run));
        assertThrows(com.apex.hrm.common.BusinessException.class, () -> payroll.review(runId));
        verify(runs, never()).save(any());
    }
}
