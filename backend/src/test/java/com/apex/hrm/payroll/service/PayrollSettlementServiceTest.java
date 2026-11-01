package com.apex.hrm.payroll.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.payroll.domain.BankFile;
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

/** Negative and partial-reconciliation coverage; actual SQL locks are checked in PostgreSQL E2E. */
class PayrollSettlementServiceTest {
    private final PayrollRunRepository runs = mock(PayrollRunRepository.class);
    private final BankFileRepository files = mock(BankFileRepository.class);
    private final PayrollBankSettlementRepository acknowledgments = mock(PayrollBankSettlementRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final BankCreditResultService bankResults = mock(BankCreditResultService.class);
    private final PayrollSettlementService service = new PayrollSettlementService(runs, files, acknowledgments, audit, bankResults);
    private final UUID runId = UUID.randomUUID();
    private final UUID fileId = UUID.randomUUID();

    private PayrollRun run(String status, String amount) {
        PayrollRun r = new PayrollRun();
        r.setId(runId);
        r.setStatus(status);
        r.setTotalNet(new BigDecimal(amount));
        when(runs.lockForSettlement(runId)).thenReturn(Optional.of(r));
        when(runs.findById(runId)).thenReturn(Optional.of(r));
        return r;
    }

    private BankFile file(String total) {
        BankFile f = new BankFile();
        f.setId(fileId);
        f.setRunId(runId);
        f.setTotalAmount(new BigDecimal(total));
        when(files.findById(fileId)).thenReturn(Optional.of(f));
        when(bankResults.acceptedAmount(fileId)).thenReturn(new BigDecimal(total));
        return f;
    }

    @Test
    void cannotRecordUnapprovedRunOrMismatchedBankExport() {
        run("COMPUTED", "200.00");
        file("200.00");
        UUID actor = UUID.randomUUID();
        assertThrows(BusinessException.class, () -> service.record(runId, fileId,
                new BigDecimal("200.00"), "REF", "EVIDENCE", actor));
        run("LOCKED", "200.00");
        assertThrows(BusinessException.class, () -> service.record(runId, fileId,
                new BigDecimal("199.99"), "REF", "EVIDENCE", actor));
        verify(acknowledgments, never()).save(any());
    }

    @Test
    void duplicateBankFileCannotBeAcknowledgedAgain() {
        run("LOCKED", "200.00");
        file("200.00");
        when(acknowledgments.existsByBankFileId(fileId)).thenReturn(true);
        assertThrows(BusinessException.class, () -> service.record(runId, fileId,
                new BigDecimal("200.00"), "REF", "EVIDENCE", UUID.randomUUID()));
    }

    @Test
    void differentConfirmerRequiredAndOverpaymentDenied() {
        run("LOCKED", "200.00");
        PayrollBankSettlement settlement = new PayrollBankSettlement();
        settlement.setId(UUID.randomUUID());
        settlement.setRunId(runId);
        settlement.setBankFileId(fileId);
        settlement.setStatus("RECORDED");
        settlement.setAcknowledgedAmount(new BigDecimal("150.00"));
        UUID recorder = UUID.randomUUID();
        settlement.setRecordedBy(recorder);
        when(acknowledgments.findById(settlement.getId())).thenReturn(Optional.of(settlement));
        when(acknowledgments.save(any(PayrollBankSettlement.class))).thenAnswer(i -> i.getArgument(0));
        assertThrows(BusinessException.class, () -> service.confirm(settlement.getId(), recorder));
        PayrollBankSettlement earlier = new PayrollBankSettlement();
        earlier.setStatus("CONFIRMED");
        earlier.setAcknowledgedAmount(new BigDecimal("100.00"));
        when(acknowledgments.findByRunId(runId)).thenReturn(List.of(earlier));
        assertThrows(BusinessException.class, () -> service.confirm(settlement.getId(), UUID.randomUUID()));
        when(acknowledgments.findByRunId(runId)).thenReturn(List.of());
        PayrollBankSettlement confirmed = service.confirm(settlement.getId(), UUID.randomUUID());
        assertEquals("CONFIRMED", confirmed.getStatus());
        assertNotNull(confirmed.getConfirmedAt());
        assertThrows(BusinessException.class, () -> service.confirm(settlement.getId(), UUID.randomUUID()));
    }

    @Test
    void partialConfirmationRemainsOutstanding() {
        run("LOCKED", "200.00");
        PayrollBankSettlement confirmed = new PayrollBankSettlement();
        confirmed.setStatus("CONFIRMED");
        confirmed.setAcknowledgedAmount(new BigDecimal("75.00"));
        PayrollBankSettlement recorded = new PayrollBankSettlement();
        recorded.setStatus("RECORDED");
        recorded.setAcknowledgedAmount(new BigDecimal("125.00"));
        when(acknowledgments.findByRunId(runId)).thenReturn(List.of(confirmed, recorded));
        var summary = service.summary(runId);
        assertEquals("PARTIAL", summary.status());
        assertEquals(0, new BigDecimal("125.00").compareTo(summary.outstanding()));
        assertEquals(0, new BigDecimal("75.00").compareTo(summary.confirmed()));
    }
}
