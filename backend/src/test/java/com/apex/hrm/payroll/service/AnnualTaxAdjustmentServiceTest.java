package com.apex.hrm.payroll.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.apex.hrm.common.BusinessException;
import com.apex.hrm.payroll.domain.Adjustment;
import com.apex.hrm.payroll.domain.AnnualTaxAdjustmentRequest;
import com.apex.hrm.payroll.domain.PayPeriod;
import com.apex.hrm.payroll.engine.AnnualCompensationLedger;
import com.apex.hrm.payroll.engine.AnnualizedTaxCalculator;
import com.apex.hrm.payroll.repo.AdjustmentRepository;
import com.apex.hrm.payroll.repo.AnnualTaxAdjustmentRequestRepository;
import com.apex.hrm.payroll.repo.PayPeriodRepository;
import com.apex.hrm.platform.service.AuditService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AnnualTaxAdjustmentServiceTest {
    private AnnualTaxReconciliationService reconciliation;
    private AnnualTaxAdjustmentRequestRepository requests;
    private PayPeriodRepository periods;
    private AdjustmentRepository adjustments;
    private AuditService audit;
    private AnnualTaxAdjustmentService service;
    private UUID employee;
    private UUID target;
    private UUID requester;
    private UUID approver;

    private BigDecimal money(String value) { return new BigDecimal(value); }

    @BeforeEach void setup() {
        reconciliation = mock(AnnualTaxReconciliationService.class);
        requests = mock(AnnualTaxAdjustmentRequestRepository.class);
        periods = mock(PayPeriodRepository.class);
        adjustments = mock(AdjustmentRepository.class);
        audit = mock(AuditService.class);
        service = new AnnualTaxAdjustmentService(reconciliation, requests, periods, adjustments, audit);
        employee = UUID.randomUUID();
        target = UUID.randomUUID();
        requester = UUID.randomUUID();
        approver = UUID.randomUUID();

        PayPeriod open = new PayPeriod();
        open.setStartDate(LocalDate.of(2027, 1, 1));
        open.setStatus("OPEN");
        when(periods.findById(target)).thenReturn(Optional.of(open));
        when(reconciliation.preview(employee, 2025)).thenReturn(
                new AnnualTaxReconciliationService.Reconciliation(employee, 2025, 12,
                        new AnnualCompensationLedger.Totals(
                                money("350000.00"), money("90000.00"), money("0.00"), money("5000.00")),
                        new AnnualizedTaxCalculator.Result(money("90000.00"), money("0.00"),
                                money("350000.00"), money("15000.00"), money("10000.00")),
                        "REVIEW_REQUIRED"));
        when(requests.saveAndFlush(any(AnnualTaxAdjustmentRequest.class))).thenAnswer(x -> {
            AnnualTaxAdjustmentRequest record = x.getArgument(0);
            record.setId(UUID.randomUUID());
            return record;
        });
        when(adjustments.saveAndFlush(any(Adjustment.class))).thenAnswer(x -> {
            Adjustment item = x.getArgument(0);
            item.setId(UUID.randomUUID());
            return item;
        });
        when(requests.save(any(AnnualTaxAdjustmentRequest.class))).thenAnswer(x -> x.getArgument(0));
    }

    private AnnualTaxAdjustmentService.Submit submitted(boolean complete, boolean benefits) {
        return new AnnualTaxAdjustmentService.Submit(employee, 2025, target, complete, benefits,
                "All historical pay slips and benefit classifications independently reconciled.");
    }

    @Test void requiresCompletenessAndBenefitAttestations() {
        assertThrows(BusinessException.class, () -> service.request(submitted(false, true), requester));
        assertThrows(BusinessException.class, () -> service.request(submitted(true, false), requester));
        verifyNoInteractions(reconciliation, requests, adjustments, audit);
    }

    @Test void requestPersistsSnapshotWithTrustedRequesterAndAudit() {
        AnnualTaxAdjustmentRequest item = service.request(submitted(true, true), requester);
        assertEquals(requester, item.getRequestedBy());
        assertEquals(employee, item.getEmployeeId());
        assertEquals(money("10000.00"), item.getAdjustmentAmount());
        assertEquals("PENDING", item.getStatus());
        verify(adjustments, never()).saveAndFlush(any());
        verify(audit).log(eq("AnnualTaxAdjustmentRequest"), eq(item.getId()), eq("REQUEST"), anyString());
    }

    @Test void preventsDuplicateTaxYearRequests() {
        when(requests.findByEmployeeIdAndTaxYear(employee, 2025))
                .thenReturn(Optional.of(new AnnualTaxAdjustmentRequest()));
        assertThrows(BusinessException.class, () -> service.request(submitted(true, true), requester));
        verifyNoInteractions(reconciliation, adjustments, audit);
    }

    @Test void rejectsLockedPayPeriod() {
        PayPeriod locked = new PayPeriod();
        locked.setStatus("LOCKED");
        locked.setStartDate(LocalDate.of(2027, 1, 1));
        when(periods.findById(target)).thenReturn(Optional.of(locked));
        assertThrows(BusinessException.class, () -> service.request(submitted(true, true), requester));
    }

    private AnnualTaxAdjustmentRequest pending() {
        AnnualTaxAdjustmentRequest record = new AnnualTaxAdjustmentRequest();
        record.setId(UUID.randomUUID());
        record.setEmployeeId(employee);
        record.setTaxYear(2025);
        record.setTargetPeriodId(target);
        record.setRequestedBy(requester);
        record.setReconciliationTax(money("15000.00"));
        record.setReconciliationWithheld(money("5000.00"));
        record.setAdjustmentAmount(money("10000.00"));
        record.setJustification("Verified year-end payroll ledger and benefits reviewed.");
        record.setStatus("PENDING");
        when(requests.lockById(record.getId())).thenReturn(Optional.of(record));
        return record;
    }

    @Test void requesterCannotApproveOwnTaxDeduction() {
        AnnualTaxAdjustmentRequest record = pending();
        assertThrows(BusinessException.class, () -> service.approve(record.getId(), requester));
        verifyNoInteractions(adjustments);
    }

    @Test void approvalCreatesExactlyOneApprovedNontaxableAdjustment() {
        AnnualTaxAdjustmentRequest record = pending();
        AnnualTaxAdjustmentRequest result = service.approve(record.getId(), approver);
        assertEquals("APPROVED", result.getStatus());
        assertEquals(approver, result.getApprovedBy());
        assertNotNull(result.getAdjustmentId());
        ArgumentCaptor<Adjustment> capture = ArgumentCaptor.forClass(Adjustment.class);
        verify(adjustments, times(1)).saveAndFlush(capture.capture());
        Adjustment adjustment = capture.getValue();
        assertEquals("WTAX_ANNUAL", adjustment.getItemCode());
        assertEquals("DEDUCTION", adjustment.getCategory());
        assertEquals(false, adjustment.getTaxable());
        assertEquals(money("10000.00"), adjustment.getAmount());
        assertEquals(target, adjustment.getTargetPeriodId());
        assertEquals(requester, adjustment.getRequestedBy());
        assertEquals(approver, adjustment.getApprovedBy());
        assertEquals("APPROVED", adjustment.getStatus());
        verify(audit).log(eq("AnnualTaxAdjustmentRequest"), eq(record.getId()), eq("APPROVE"), anyString());
    }

    @Test void changedLedgerBlocksApprovalBeforeAnyAdjustmentIsCreated() {
        AnnualTaxAdjustmentRequest record = pending();
        when(reconciliation.preview(employee, 2025)).thenReturn(
                new AnnualTaxReconciliationService.Reconciliation(employee, 2025, 12,
                        new AnnualCompensationLedger.Totals(
                                money("350000.00"), money("90000.00"), money("0.00"), money("6000.00")),
                        new AnnualizedTaxCalculator.Result(money("90000.00"), money("0.00"),
                                money("350000.00"), money("15000.00"), money("9000.00")),
                        "REVIEW_REQUIRED"));
        assertThrows(BusinessException.class, () -> service.approve(record.getId(), approver));
        verifyNoInteractions(adjustments);
        verify(requests, never()).save(any());
    }

    @Test void rejectedRequestCannotLaterBeApproved() {
        AnnualTaxAdjustmentRequest record = pending();
        AnnualTaxAdjustmentRequest rejected = service.reject(record.getId(), approver);
        assertEquals("REJECTED", rejected.getStatus());
        assertThrows(BusinessException.class, () -> service.approve(record.getId(), UUID.randomUUID()));
        verifyNoInteractions(adjustments);
    }
}
