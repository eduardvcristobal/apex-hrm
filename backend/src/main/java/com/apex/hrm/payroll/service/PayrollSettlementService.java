package com.apex.hrm.payroll.service;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.payroll.domain.BankFile;
import com.apex.hrm.payroll.domain.PayrollBankSettlement;
import com.apex.hrm.payroll.domain.PayrollRun;
import com.apex.hrm.payroll.repo.BankFileRepository;
import com.apex.hrm.payroll.repo.PayrollBankSettlementRepository;
import com.apex.hrm.payroll.repo.PayrollRunRepository;
import com.apex.hrm.platform.service.AuditService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Human-recorded bank settlement evidence with independent confirmation; no bank API is implied. */
@Service
public class PayrollSettlementService {
    public record Summary(BigDecimal expected, BigDecimal confirmed, BigDecimal outstanding,
            String status, List<PayrollBankSettlement> acknowledgments) {}

    private final PayrollRunRepository runs;
    private final BankFileRepository bankFiles;
    private final PayrollBankSettlementRepository settlements;
    private final AuditService audit;
    private final BankCreditResultService bankCreditResults;

    public PayrollSettlementService(PayrollRunRepository runs, BankFileRepository bankFiles,
            PayrollBankSettlementRepository settlements, AuditService audit,
            BankCreditResultService bankCreditResults) {
        this.runs = runs;
        this.bankFiles = bankFiles;
        this.settlements = settlements;
        this.audit = audit;
        this.bankCreditResults = bankCreditResults;
    }

    @Transactional
    public PayrollBankSettlement record(UUID runId, UUID bankFileId, BigDecimal amount,
            String bankReference, String evidenceReference, UUID actor) {
        PayrollRun run = lockedRun(runId);
        requireLocked(run);
        if (actor == null) throw new BusinessException("Authenticated settlement recorder is required");
        if (bankReference == null || bankReference.isBlank() || bankReference.length() > 120
                || evidenceReference == null || evidenceReference.isBlank() || evidenceReference.length() > 255) {
            throw new BusinessException("Bank reference and evidence reference are required and must fit their limits");
        }
        if (!positiveCents(amount)) throw new BusinessException("Acknowledged amount must be positive and limited to cents");
        BankFile file = bankFiles.findById(bankFileId)
                .orElseThrow(() -> new NotFoundException("Bank file", bankFileId));
        if (!runId.equals(file.getRunId())) throw new BusinessException("Bank file belongs to another payroll run");
        BigDecimal acceptedAmount = bankCreditResults.acceptedAmount(bankFileId);
        if (acceptedAmount == null || acceptedAmount.signum() <= 0) {
            throw new BusinessException("Import a complete bank response with accepted credits before recording settlement");
        }
        if (money(amount).compareTo(money(acceptedAmount)) != 0) {
            throw new BusinessException("Acknowledged amount must exactly equal the accepted employee credits");
        }
        if (settlements.existsByBankFileId(bankFileId)) {
            throw new BusinessException("A bank file can only be acknowledged once");
        }
        PayrollBankSettlement row = new PayrollBankSettlement();
        row.setRunId(runId);
        row.setBankFileId(bankFileId);
        row.setAcknowledgedAmount(money(amount));
        row.setBankReference(bankReference.strip());
        row.setEvidenceReference(evidenceReference.strip());
        row.setRecordedBy(actor);
        row = settlements.saveAndFlush(row);
        audit.log("PayrollBankSettlement", row.getId(), "RECORD",
                "Bank reference " + row.getBankReference() + "; acknowledged amount " + row.getAcknowledgedAmount()
                        + "; evidence reference " + row.getEvidenceReference());
        return row;
    }

    @Transactional
    public PayrollBankSettlement confirm(UUID settlementId, UUID actor) {
        PayrollBankSettlement row = settlement(settlementId);
        PayrollRun run = lockedRun(row.getRunId());
        requireLocked(run);
        if (actor == null || actor.equals(row.getRecordedBy())) {
            throw new BusinessException("A different authenticated payroll administrator must confirm settlement");
        }
        if (!"RECORDED".equals(row.getStatus())) throw new BusinessException("Settlement is no longer pending");
        BigDecimal confirmed = confirmedTotal(row.getRunId());
        if (confirmed.add(row.getAcknowledgedAmount()).compareTo(money(run.getTotalNet())) > 0) {
            throw new BusinessException("Confirmed settlement would exceed net payroll");
        }
        row.setStatus("CONFIRMED");
        row.setConfirmedBy(actor);
        row.setConfirmedAt(Instant.now());
        row = settlements.save(row);
        audit.log("PayrollBankSettlement", row.getId(), "CONFIRM", "Confirmed by " + actor);
        return row;
    }

    @Transactional
    public PayrollBankSettlement reject(UUID settlementId, String reason, String evidence, UUID actor) {
        PayrollBankSettlement row = settlement(settlementId);
        PayrollRun run = lockedRun(row.getRunId());
        requireLocked(run);
        if (!"RECORDED".equals(row.getStatus())) throw new BusinessException("Only unconfirmed acknowledgments can be rejected");
        if (actor == null || actor.equals(row.getRecordedBy())) throw new BusinessException("A different operator must reject the acknowledgment");
        validateException(reason, evidence);
        row.setStatus("REJECTED");
        addException(row, reason, evidence, actor);
        row = settlements.save(row);
        audit.log("PayrollBankSettlement", row.getId(), "REJECT", "Rejected; evidence reference " + evidence.strip());
        return row;
    }

    @Transactional
    public PayrollBankSettlement reverse(UUID settlementId, String reason, String evidence, UUID actor) {
        PayrollBankSettlement row = settlement(settlementId);
        PayrollRun run = lockedRun(row.getRunId());
        if (!"LOCKED".equals(run.getStatus()) && !"PAID".equals(run.getStatus())) {
            throw new BusinessException("Only locked or paid payroll acknowledgments can be reversed");
        }
        if (!"CONFIRMED".equals(row.getStatus())) throw new BusinessException("Only confirmed acknowledgments can be reversed");
        if (actor == null || actor.equals(row.getConfirmedBy())) throw new BusinessException("A different operator must authorize the reversal");
        validateException(reason, evidence);
        row.setStatus("REVERSED");
        addException(row, reason, evidence, actor);
        row = settlements.save(row);
        audit.log("PayrollBankSettlement", row.getId(), "REVERSE",
                "Reversed; evidence reference " + evidence.strip() + "; payroll remains " + run.getStatus());
        return row;
    }

    @Transactional(readOnly = true)
    public Summary summary(UUID runId) {
        PayrollRun run = runs.findById(runId).orElseThrow(() -> new NotFoundException("Payroll run", runId));
        List<PayrollBankSettlement> rows = settlements.findByRunIdOrderByCreatedAtAsc(runId);
        BigDecimal confirmed = rows.stream().filter(row -> "CONFIRMED".equals(row.getStatus()))
                .map(PayrollBankSettlement::getAcknowledgedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expected = money(run.getTotalNet());
        BigDecimal outstanding = expected.subtract(confirmed);
        String status = "PAID".equals(run.getStatus()) && outstanding.signum() > 0 ? "DISPUTED"
                : confirmed.signum() == 0 ? "UNSETTLED" : outstanding.signum() == 0 ? "RECONCILED" : "PARTIAL";
        return new Summary(expected, confirmed, outstanding, status, rows);
    }

    @Transactional(readOnly = true)
    public BigDecimal confirmedTotal(UUID runId) {
        return settlements.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .filter(row -> "CONFIRMED".equals(row.getStatus()))
                .map(PayrollBankSettlement::getAcknowledgedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private PayrollBankSettlement settlement(UUID id) {
        return settlements.findById(id).orElseThrow(() -> new NotFoundException("Payroll settlement", id));
    }

    private PayrollRun lockedRun(UUID id) {
        return runs.lockForSettlement(id).orElseThrow(() -> new NotFoundException("Payroll run", id));
    }

    private static void requireLocked(PayrollRun run) {
        if (!"LOCKED".equals(run.getStatus())) throw new BusinessException("Settlement requires locked payroll");
    }

    private static boolean positiveCents(BigDecimal amount) {
        return amount != null && amount.signum() > 0 && amount.stripTrailingZeros().scale() <= 2;
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static void validateException(String reason, String evidence) {
        if (reason == null || reason.isBlank() || reason.length() > 2000
                || evidence == null || evidence.isBlank() || evidence.length() > 255) {
            throw new BusinessException("Exception reason (max 2000) and bank evidence reference (max 255) are required");
        }
    }

    private static void addException(PayrollBankSettlement row, String reason, String evidence, UUID actor) {
        row.setExceptionReason(reason.strip());
        row.setExceptionEvidenceReference(evidence.strip());
        row.setExceptionBy(actor);
        row.setExceptionAt(Instant.now());
    }
}
