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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual bank statement acknowledgment. Never interpret exported files as settled funds.
 * Recording and confirmation require different human identities.
 */
@Service
public class PayrollSettlementService {
    public record Summary(BigDecimal expected, BigDecimal confirmed, BigDecimal outstanding,
                          String status, List<PayrollBankSettlement> acknowledgments) {}

    private final PayrollRunRepository runs;
    private final BankFileRepository bankFiles;
    private final PayrollBankSettlementRepository settlements;
    private final AuditService audit;

    public PayrollSettlementService(PayrollRunRepository runs, BankFileRepository bankFiles,
                                    PayrollBankSettlementRepository settlements, AuditService audit) {
        this.runs = runs;
        this.bankFiles = bankFiles;
        this.settlements = settlements;
        this.audit = audit;
    }

    @Transactional
    public PayrollBankSettlement record(UUID runId, UUID bankFileId, BigDecimal amount,
                                        String bankReference, String evidenceReference, UUID actor) {
        PayrollRun run = lockedRun(runId);
        requireLockedOrPaid(run);
        if (actor == null) throw new BusinessException("Authenticated recorder required");
        if (bankReference == null || bankReference.isBlank() || evidenceReference == null || evidenceReference.isBlank()) {
            throw new BusinessException("Bank confirmation reference and evidence reference are required");
        }
        if (amount == null || amount.stripTrailingZeros().scale() > 2 || amount.signum() <= 0) {
            throw new BusinessException("Acknowledged amount must be positive, with no more than two decimal places");
        }
        BankFile bankFile = bankFiles.findById(bankFileId).orElseThrow(() -> new NotFoundException("Bank file", bankFileId));
        if (!runId.equals(bankFile.getRunId())) throw new BusinessException("Bank file belongs to another payroll run");
        if (amount.compareTo(bankFile.getTotalAmount()) != 0) {
            throw new BusinessException("Acknowledgment must exactly equal the exported bank file total");
        }
        if (settlements.existsByBankFileId(bankFileId)) {
            throw new BusinessException("A bank file can only be acknowledged once");
        }
        PayrollBankSettlement row = new PayrollBankSettlement();
        row.setRunId(runId);
        row.setBankFileId(bankFileId);
        row.setAcknowledgedAmount(amount);
        row.setBankReference(bankReference.strip());
        row.setEvidenceReference(evidenceReference.strip());
        row.setRecordedBy(actor);
        row = settlements.save(row);
        audit.log("PayrollBankSettlement", row.getId(), "RECORD",
                "Bank " + bankReference + " amount " + amount + "; evidence " + evidenceReference);
        return row;
    }

    @Transactional
    public PayrollBankSettlement confirm(UUID settlementId, UUID actor) {
        PayrollBankSettlement row = settlements.findById(settlementId)
                .orElseThrow(() -> new NotFoundException("Payroll settlement", settlementId));
        PayrollRun run = lockedRun(row.getRunId());
        requireLockedOrPaid(run);
        if (actor == null || actor.equals(row.getRecordedBy())) {
            throw new BusinessException("A different authenticated approver must confirm settlement");
        }
        if (!"RECORDED".equals(row.getStatus())) throw new BusinessException("Settlement already confirmed");
        // Reject duplicate or overlapping exported batches that exceed net payroll.
        BigDecimal confirmed = confirmedTotal(row.getRunId());
        if (confirmed.add(row.getAcknowledgedAmount()).compareTo(run.getTotalNet()) > 0) {
            throw new BusinessException("Confirmed settlement would exceed net payroll");
        }
        row.setStatus("CONFIRMED");
        row.setConfirmedBy(actor);
        row.setConfirmedAt(Instant.now());
        row = settlements.save(row);
        audit.log("PayrollBankSettlement", row.getId(), "CONFIRM",
                "Acknowledgment confirmed by " + actor + " amount " + row.getAcknowledgedAmount());
        return row;
    }

    @Transactional(readOnly = true)
    public Summary summary(UUID runId) {
        PayrollRun run = runs.findById(runId).orElseThrow(() -> new NotFoundException("Payroll run", runId));
        List<PayrollBankSettlement> records = settlements.findByRunId(runId);
        BigDecimal confirmed = records.stream().filter(s -> "CONFIRMED".equals(s.getStatus()))
                .map(PayrollBankSettlement::getAcknowledgedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal outstanding = run.getTotalNet().subtract(confirmed);
        String status = "PAID".equals(run.getStatus()) && outstanding.signum() > 0 ? "DISPUTED"
                : confirmed.signum() == 0 ? "UNSETTLED" : outstanding.signum() == 0 ? "RECONCILED" : "PARTIAL";
        return new Summary(run.getTotalNet(), confirmed, outstanding, status, records);
    }

    @Transactional(readOnly = true)
    public BigDecimal confirmedTotal(UUID runId) {
        return settlements.findByRunId(runId).stream().filter(s -> "CONFIRMED".equals(s.getStatus()))
                .map(PayrollBankSettlement::getAcknowledgedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }


    /**
     * Reject a pending, unconfirmed bank acknowledgment. Requires a second operator;
     * the rejected file is never silently reused, and the next recovery export must be new.
     */
    @Transactional
    public PayrollBankSettlement reject(UUID settlementId, String reason, String evidence, UUID actor) {
        PayrollBankSettlement row = settlements.findById(settlementId)
                .orElseThrow(() -> new NotFoundException("Payroll settlement", settlementId));
        PayrollRun run = lockedRun(row.getRunId());
        if (!"LOCKED".equals(run.getStatus())) {
            throw new BusinessException("Rejection requires a locked, unpaid run");
        }
        if (!"RECORDED".equals(row.getStatus())) throw new BusinessException("Only pending acknowledgments can be rejected");
        if (actor == null || actor.equals(row.getRecordedBy())) {
            throw new BusinessException("A separate operator must reject the acknowledgment");
        }
        validateExceptionEvidence(reason, evidence);
        row.setStatus("REJECTED");
        row.setExceptionReason(reason.strip());
        row.setExceptionEvidenceReference(evidence.strip());
        row.setExceptionBy(actor);
        row.setExceptionAt(Instant.now());
        row = settlements.save(row);
        audit.log("PayrollBankSettlement", row.getId(), "REJECT",
                "Rejected acknowledgment; evidence " + row.getExceptionEvidenceReference());
        return row;
    }

    /**
     * Record a bank-supported reversal of previously confirmed funds.
     * If the run was marked PAID, leave the original status intact for historical audit;
     * summary becomes DISPUTED, and an explicit recovery process is mandatory.
     */
    @Transactional
    public PayrollBankSettlement reverse(UUID settlementId, String reason, String evidence, UUID actor) {
        PayrollBankSettlement row = settlements.findById(settlementId)
                .orElseThrow(() -> new NotFoundException("Payroll settlement", settlementId));
        PayrollRun run = lockedRun(row.getRunId());
        if (!"LOCKED".equals(run.getStatus()) && !"PAID".equals(run.getStatus())) {
            throw new BusinessException("Only locked or paid payroll acknowledgments can be reversed");
        }
        if (!"CONFIRMED".equals(row.getStatus())) throw new BusinessException("Only confirmed acknowledgments can be reversed");
        if (actor == null || actor.equals(row.getConfirmedBy())) {
            throw new BusinessException("A different operator must authorize reversal");
        }
        validateExceptionEvidence(reason, evidence);
        row.setStatus("REVERSED");
        row.setExceptionReason(reason.strip());
        row.setExceptionEvidenceReference(evidence.strip());
        row.setExceptionBy(actor);
        row.setExceptionAt(Instant.now());
        row = settlements.save(row);
        audit.log("PayrollBankSettlement", row.getId(), "REVERSE",
                "Bank reversal recorded; evidence " + row.getExceptionEvidenceReference()
                  + "; payroll status " + run.getStatus());
        return row;
    }

    private static void validateExceptionEvidence(String reason, String evidence) {
        if (reason == null || reason.isBlank() || evidence == null || evidence.isBlank()) {
            throw new BusinessException("Exception reason and bank evidence reference are required");
        }
        if (evidence.length() > 255) throw new BusinessException("Evidence reference is too long");
    }

    private PayrollRun lockedRun(UUID runId) {
        return runs.lockForSettlement(runId).orElseThrow(() -> new NotFoundException("Payroll run", runId));
    }

    private static void requireLockedOrPaid(PayrollRun run) {
        if (!"LOCKED".equals(run.getStatus())) {
            throw new BusinessException("Settlement requires a locked, not-yet-paid payroll run");
        }
    }
}
