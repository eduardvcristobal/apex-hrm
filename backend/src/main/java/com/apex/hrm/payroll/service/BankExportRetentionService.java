package com.apex.hrm.payroll.service;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.common.TenantContext;
import com.apex.hrm.payroll.domain.BankFile;
import com.apex.hrm.payroll.domain.PayrollRun;
import com.apex.hrm.payroll.repo.BankCreditImportRepository;
import com.apex.hrm.payroll.repo.BankCreditResultRepository;
import com.apex.hrm.payroll.repo.BankFileRepository;
import com.apex.hrm.payroll.repo.PayrollRunRepository;
import com.apex.hrm.platform.service.AuditService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit retention and legal hold; no scheduled or automatic bank-export deletion. */
@Service
public class BankExportRetentionService {
    private final BankFileRepository files;
    private final PayrollRunRepository runs;
    private final PayrollSettlementService settlements;
    private final BankCreditImportRepository imports;
    private final BankCreditResultRepository creditResults;
    private final AuditService audit;
    private final int minimumDays;

    public BankExportRetentionService(BankFileRepository files, PayrollRunRepository runs,
            PayrollSettlementService settlements, BankCreditImportRepository imports,
            BankCreditResultRepository creditResults, AuditService audit,
            @Value("${HRM_BANK_EXPORT_RETENTION_DAYS:3650}") int minimumDays) {
        if (minimumDays < 365) throw new IllegalStateException("Bank export retention cannot be less than 365 days");
        this.files = files;
        this.runs = runs;
        this.settlements = settlements;
        this.imports = imports;
        this.creditResults = creditResults;
        this.audit = audit;
        this.minimumDays = minimumDays;
    }

    public record Status(UUID fileId, boolean legalHold, boolean purged,
                         Instant createdAt, Instant eligibleOn, String sha256) {}

    @Transactional(readOnly = true)
    public Status status(UUID fileId) {
        BankFile file = load(fileId);
        return new Status(file.getId(), Boolean.TRUE.equals(file.getLegalHold()),
                file.getPurgedAt() != null, file.getCreatedAt(),
                file.getCreatedAt() == null ? null : file.getCreatedAt().plus(Duration.ofDays(minimumDays)),
                file.getContentSha256());
    }

    @Transactional
    public Status setLegalHold(UUID fileId, boolean hold, String reason, UUID actor) {
        ensureActor(actor, reason);
        BankFile file = load(fileId);
        if (file.getPurgedAt() != null) throw new BusinessException("Cannot change hold after payload purge");
        file.setLegalHold(hold);
        files.saveAndFlush(file);
        audit.log("BankFile", fileId, hold ? "LEGAL_HOLD_SET" : "LEGAL_HOLD_RELEASED",
            "Actor " + actor + ", reason: " + reason.strip());
        return status(fileId);
    }

    @Transactional
    public Status purge(UUID fileId, String reason, UUID actor) {
        ensureActor(actor, reason);
        BankFile file = load(fileId);
        // Row-lock the payroll run to serialize with settlement status transitions.
        PayrollRun run = runs.lockForSettlement(file.getRunId())
            .orElseThrow(() -> new NotFoundException("Payroll run", file.getRunId()));
        if (!"PAID".equals(run.getStatus()))
            throw new BusinessException("Bank export can only be purged after payroll is PAID");
        if (file.getPurgedAt() != null) throw new BusinessException("Bank export was already purged");
        if (Boolean.TRUE.equals(file.getLegalHold()))
            throw new BusinessException("Bank export is on legal hold");
        if (file.getCreatedAt() == null
                || file.getCreatedAt().isAfter(Instant.now().minus(Duration.ofDays(minimumDays))))
            throw new BusinessException("Bank export has not reached minimum retention age");
        if (!imports.existsByBankFileId(fileId)
                || creditResults.findByBankFileId(fileId).size() != file.getRecordCount())
            throw new BusinessException("Complete employee-level bank response is required before purge");
        if (!"RECONCILED".equals(settlements.summary(file.getRunId()).status()))
            throw new BusinessException("Entire payroll must be reconciled before export purge");
        if (file.getContentCiphertext() == null || file.getContentSha256() == null)
            throw new BusinessException("Export payload and SHA-256 evidence required before purge");
        file.setContentCiphertext(null);
        file.setPurgedAt(Instant.now());
        files.saveAndFlush(file);
        audit.log("BankFile", fileId, "PURGE", "Authenticated finance administrator " + actor
                + " destroyed ciphertext after retention; digest retained; reason: " + reason.strip());
        return status(fileId);
    }

    private BankFile load(UUID id) {
        BankFile file = files.findById(id).orElseThrow(() -> new NotFoundException("Bank file", id));
        UUID tenant = TenantContext.get();
        if (tenant == null || !tenant.equals(file.getTenantId()))
            throw new NotFoundException("Bank file", id);
        return file;
    }

    private static void ensureActor(UUID actor, String reason) {
        if (actor == null) throw new BusinessException("Authenticated administrator required");
        if (reason == null || reason.isBlank() || reason.strip().length() < 8 || reason.length() > 2000)
            throw new BusinessException("An explicit retention action reason (8-2000 chars) is required");
    }
}
