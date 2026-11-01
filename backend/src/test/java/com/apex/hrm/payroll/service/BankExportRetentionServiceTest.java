package com.apex.hrm.payroll.service;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.common.TenantContext;
import com.apex.hrm.payroll.domain.BankFile;
import com.apex.hrm.payroll.domain.BankCreditResult;
import com.apex.hrm.payroll.domain.PayrollRun;
import com.apex.hrm.payroll.repo.BankCreditImportRepository;
import com.apex.hrm.payroll.repo.BankCreditResultRepository;
import com.apex.hrm.payroll.repo.BankFileRepository;
import com.apex.hrm.payroll.repo.PayrollRunRepository;
import com.apex.hrm.platform.service.AuditService;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class BankExportRetentionServiceTest {
    @Mock BankFileRepository files;
    @Mock PayrollRunRepository runs;
    @Mock PayrollSettlementService settlements;
    @Mock BankCreditImportRepository imports;
    @Mock BankCreditResultRepository credits;
    @Mock AuditService audit;
    private BankExportRetentionService service;
    private BankFile file;
    private PayrollRun run;
    private UUID actor;

    @BeforeEach void start() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        file = new BankFile();
        file.setId(UUID.randomUUID());
        file.setTenantId(tenant);
        file.setRunId(UUID.randomUUID());
        file.setCreatedAt(Instant.now().minus(Duration.ofDays(3660)));
        file.setRecordCount(1);
        file.setContentCiphertext("e1:k1:test");
        file.setContentSha256("a".repeat(64));
        run = new PayrollRun();
        run.setStatus("PAID");
        actor = UUID.randomUUID();
        service = new BankExportRetentionService(files, runs, settlements, imports, credits, audit, 3650);
    }
    @AfterEach void finish() { TenantContext.clear(); }

    private void fileExists() {
        when(files.findById(file.getId())).thenReturn(Optional.of(file));
    }
    private void paidRun() {
        when(runs.lockForSettlement(file.getRunId())).thenReturn(Optional.of(run));
    }
    @Test void deniesCrossTenantBankFile() {
        fileExists();
        TenantContext.set(UUID.randomUUID());
        assertThrows(NotFoundException.class, () -> service.status(file.getId()));
        verify(files, never()).saveAndFlush(any());
    }

    @Test void deniesEarlyPurgeAndLegalHold() {
        file.setCreatedAt(Instant.now().minus(Duration.ofDays(100)));
        fileExists(); paidRun();
        assertThrows(BusinessException.class, () -> service.purge(file.getId(), "Finance retention review", actor));
        verify(files, never()).saveAndFlush(any());
    }

    @Test void legalHoldPreventsPurgeEvenAfterAgeGate() {
        file.setLegalHold(true);
        fileExists(); paidRun();
        assertThrows(BusinessException.class, () -> service.purge(file.getId(), "Finance retention review", actor));
        verify(files, never()).saveAndFlush(any());
    }

    @Test void deniesPurgeForUnpaidPayroll() {
        run.setStatus("LOCKED");
        fileExists(); paidRun();
        assertThrows(BusinessException.class, () -> service.purge(file.getId(), "Finance retention review", actor));
    }

    @Test void deniesWhenResponseEvidenceMissing() {
        fileExists(); paidRun();
        when(imports.existsByBankFileId(file.getId())).thenReturn(false);
        assertThrows(BusinessException.class, () -> service.purge(file.getId(), "Finance retention review", actor));
        verify(files, never()).saveAndFlush(any());
    }

    @Test void purgesOnlyAfterReconciledAndCompleteImport() {
        fileExists(); paidRun();
        when(imports.existsByBankFileId(file.getId())).thenReturn(true);
        when(credits.findByBankFileId(file.getId())).thenReturn(List.of(new BankCreditResult()));
        when(settlements.summary(file.getRunId())).thenReturn(
            new PayrollSettlementService.Summary(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO,
                "RECONCILED", List.of()));
        when(files.saveAndFlush(file)).thenReturn(file);
        var result = service.purge(file.getId(), "Retention expiry fully reviewed", actor);
        assertTrue(result.purged());
        assertNull(file.getContentCiphertext());
        assertNotNull(file.getContentSha256());
        verify(audit).log(eq("BankFile"), eq(file.getId()), eq("PURGE"), anyString());
    }

    @Test void cannotRemovePurgedLegalHoldOrProvideBlankReason() {
        file.setPurgedAt(Instant.now());
        file.setContentCiphertext(null);
        fileExists();
        assertThrows(BusinessException.class, () ->
            service.setLegalHold(file.getId(), false, "Approved retention review", actor));
        assertThrows(BusinessException.class, () ->
            service.purge(file.getId(), "short", actor));
    }
}
