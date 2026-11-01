package com.apex.hrm.payroll.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.payroll.domain.PayrollBankSettlement;
import com.apex.hrm.payroll.domain.PayrollRun;
import com.apex.hrm.payroll.repo.BankFileRepository;
import com.apex.hrm.payroll.repo.PayrollBankSettlementRepository;
import com.apex.hrm.payroll.repo.PayrollRunRepository;
import com.apex.hrm.platform.service.AuditService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayrollSettlementExceptionTest {
    private final PayrollRunRepository runs = mock(PayrollRunRepository.class);
    private final BankFileRepository files = mock(BankFileRepository.class);
    private final PayrollBankSettlementRepository records = mock(PayrollBankSettlementRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final PayrollSettlementService service = new PayrollSettlementService(runs, files, records, audit, mock(BankCreditResultService.class));
    private final UUID runId = UUID.randomUUID();
    private final UUID settlementId = UUID.randomUUID();
    private final UUID recorder = UUID.randomUUID();
    private final UUID confirmer = UUID.randomUUID();

    private PayrollBankSettlement fixture(String runStatus, String settlementStatus) {
        PayrollRun run = new PayrollRun();
        run.setId(runId);
        run.setStatus(runStatus);
        run.setTotalNet(new BigDecimal("200.00"));
        when(runs.lockForSettlement(runId)).thenReturn(Optional.of(run));
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        PayrollBankSettlement row = new PayrollBankSettlement();
        row.setId(settlementId);
        row.setRunId(runId);
        row.setStatus(settlementStatus);
        row.setRecordedBy(recorder);
        row.setAcknowledgedAmount(new BigDecimal("200.00"));
        if ("CONFIRMED".equals(settlementStatus)) row.setConfirmedBy(confirmer);
        when(records.findById(settlementId)).thenReturn(Optional.of(row));
        when(records.save(any(PayrollBankSettlement.class))).thenAnswer(i -> i.getArgument(0));
        when(records.findByRunId(runId)).thenReturn(List.of(row));
        return row;
    }

    @Test
    void rejectPendingRequiresIndependentOperatorAndEvidence() {
        PayrollBankSettlement pending = fixture("LOCKED", "RECORDED");
        assertThrows(BusinessException.class, () -> service.reject(settlementId, "rejected", "bank-1", recorder));
        assertThrows(BusinessException.class, () -> service.reject(settlementId, " ", "bank-1", UUID.randomUUID()));
        PayrollBankSettlement rejected = service.reject(settlementId, "bank rejected batch", "statement-123", confirmer);
        assertSame(pending, rejected);
        assertEquals("REJECTED", rejected.getStatus());
        assertEquals(confirmer, rejected.getExceptionBy());
        assertNotNull(rejected.getExceptionAt());
        assertEquals("UNSETTLED", service.summary(runId).status());
        assertThrows(BusinessException.class, () -> service.confirm(settlementId, UUID.randomUUID()));
        assertThrows(BusinessException.class, () -> service.reject(settlementId, "again", "bank-2", UUID.randomUUID()));
    }

    @Test
    void reverseConfirmedRequiresDifferentActorAndPreservesAudit() {
        fixture("LOCKED", "CONFIRMED");
        assertThrows(BusinessException.class, () -> service.reverse(settlementId, "rejected credit", "bank", confirmer));
        PayrollBankSettlement reversed = service.reverse(settlementId, "returned funds", "bank-return-1", recorder);
        assertEquals("REVERSED", reversed.getStatus());
        assertEquals("UNSETTLED", service.summary(runId).status());
        assertThrows(BusinessException.class, () -> service.reverse(settlementId, "again", "bank", UUID.randomUUID()));
    }

    @Test
    void reversingAfterPaidFlagsDisputedWithoutRewritingPaidHistory() {
        fixture("PAID", "CONFIRMED");
        service.reverse(settlementId, "post-payment return", "bank-return-2", recorder);
        var result = service.summary(runId);
        assertEquals("DISPUTED", result.status());
        assertEquals(0, new BigDecimal("200.00").compareTo(result.outstanding()));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.confirmed()));
    }

    @Test
    void pendingRejectionCannotBeUsedAfterPaid() {
        fixture("PAID", "RECORDED");
        assertThrows(BusinessException.class, () -> service.reject(settlementId, "reason", "bank", confirmer));
    }
}
