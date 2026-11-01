package com.apex.hrm.payroll.service;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.payroll.domain.Adjustment;
import com.apex.hrm.payroll.domain.AnnualTaxAdjustmentRequest;
import com.apex.hrm.payroll.domain.PayPeriod;
import com.apex.hrm.payroll.repo.AdjustmentRepository;
import com.apex.hrm.payroll.repo.AnnualTaxAdjustmentRequestRepository;
import com.apex.hrm.payroll.repo.PayPeriodRepository;
import com.apex.hrm.platform.service.AuditService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dual-control, positive-only annual withholding request. Refunds and incomplete years
 * remain review-only until an explicit legally reviewed workflow exists.
 */
@Service
public class AnnualTaxAdjustmentService {
    private final AnnualTaxReconciliationService reconciliations;
    private final AnnualTaxAdjustmentRequestRepository requests;
    private final PayPeriodRepository periods;
    private final AdjustmentRepository adjustments;
    private final AuditService audit;

    public AnnualTaxAdjustmentService(AnnualTaxReconciliationService reconciliations,
            AnnualTaxAdjustmentRequestRepository requests, PayPeriodRepository periods,
            AdjustmentRepository adjustments, AuditService audit) {
        this.reconciliations = reconciliations;
        this.requests = requests;
        this.periods = periods;
        this.adjustments = adjustments;
        this.audit = audit;
    }

    public record Submit(UUID employeeId, int taxYear, UUID targetPeriodId,
            boolean completeYearVerified, boolean benefitsVerified, String justification) {}

    @Transactional
    public AnnualTaxAdjustmentRequest request(Submit form, UUID requester) {
        if (form == null || requester == null || form.employeeId() == null ||
                form.targetPeriodId() == null)
            throw new BusinessException("Employee, target period and authenticated requester are required");
        validateYear(form.taxYear());
        if (!form.completeYearVerified() || !form.benefitsVerified())
            throw new BusinessException("Payroll-year completeness and benefit review must be attested");
        if (form.justification() == null || form.justification().trim().length() < 20 ||
                form.justification().length() > 2000)
            throw new BusinessException("Provide a 20-2000 character reconciliation justification");
        if (requests.findByEmployeeIdAndTaxYear(form.employeeId(), form.taxYear()).isPresent())
            throw new BusinessException("An annual tax request already exists for this employee and year");
        verifyTarget(form.targetPeriodId(), form.taxYear());
        var tax = reconciliations.preview(form.employeeId(), form.taxYear());
        BigDecimal due = tax.tax().additionalWithholdingOrRefund();
        if (due.signum() <= 0)
            throw new BusinessException("Only positive annual withholding can be requested; refunds require separate review");
        AnnualTaxAdjustmentRequest result = new AnnualTaxAdjustmentRequest();
        result.setEmployeeId(form.employeeId());
        result.setTaxYear(form.taxYear());
        result.setTargetPeriodId(form.targetPeriodId());
        result.setRequestedBy(requester);
        result.setReconciliationTax(tax.tax().annualTax());
        result.setReconciliationWithheld(tax.ledger().withheld());
        result.setAdjustmentAmount(due);
        result.setJustification(form.justification().trim());
        result.setAttestedCompleteYear(true);
        result.setAttestedBenefitsReviewed(true);
        result = requests.saveAndFlush(result);
        audit.log("AnnualTaxAdjustmentRequest", result.getId(), "REQUEST",
                "Annual tax " + form.taxYear() + " employee=" + form.employeeId() +
                " withholding=" + due + " target=" + form.targetPeriodId());
        return result;
    }

    @Transactional
    public AnnualTaxAdjustmentRequest approve(UUID id, UUID approver) {
        AnnualTaxAdjustmentRequest item = pending(id);
        if (approver == null || approver.equals(item.getRequestedBy()))
            throw new BusinessException("A different authenticated payroll administrator must approve");
        validateYear(item.getTaxYear());
        verifyTarget(item.getTargetPeriodId(), item.getTaxYear());
        var current = reconciliations.preview(item.getEmployeeId(), item.getTaxYear());
        if (current.tax().additionalWithholdingOrRefund().compareTo(item.getAdjustmentAmount()) != 0 ||
                current.tax().annualTax().compareTo(item.getReconciliationTax()) != 0 ||
                current.ledger().withheld().compareTo(item.getReconciliationWithheld()) != 0)
            throw new BusinessException("Underlying payroll ledger changed; do not approve a stale annual tax request");
        Adjustment adjustment = new Adjustment();
        adjustment.setEmployeeId(item.getEmployeeId());
        adjustment.setItemCode("WTAX_ANNUAL");
        adjustment.setDescription("Approved annual withholding for tax year " + item.getTaxYear());
        adjustment.setCategory("DEDUCTION");
        adjustment.setAmount(item.getAdjustmentAmount());
        adjustment.setTaxable(false);
        adjustment.setReason("Annual tax adjustment request " + item.getId() + ": " + item.getJustification());
        adjustment.setTargetPeriodId(item.getTargetPeriodId());
        adjustment.setRequestedBy(item.getRequestedBy());
        adjustment.setApprovedBy(approver);
        adjustment.setApprovedAt(Instant.now());
        adjustment.setStatus("APPROVED");
        adjustment = adjustments.saveAndFlush(adjustment);
        item.setAdjustmentId(adjustment.getId());
        item.setApprovedAt(Instant.now());
        item.setApprovedBy(approver);
        item.setStatus("APPROVED");
        item = requests.save(item);
        audit.log("AnnualTaxAdjustmentRequest", item.getId(), "APPROVE",
                "Created approved WTAX_ANNUAL adjustment=" + adjustment.getId() +
                " by=" + approver + " amount=" + item.getAdjustmentAmount());
        return item;
    }

    @Transactional
    public AnnualTaxAdjustmentRequest reject(UUID id, UUID actor) {
        AnnualTaxAdjustmentRequest item = pending(id);
        if (actor == null || actor.equals(item.getRequestedBy()))
            throw new BusinessException("A different authenticated payroll administrator must reject");
        item.setStatus("REJECTED");
        item.setApprovedBy(actor);
        item.setApprovedAt(Instant.now());
        item = requests.save(item);
        audit.log("AnnualTaxAdjustmentRequest", item.getId(), "REJECT",
                "Rejected by=" + actor + " taxYear=" + item.getTaxYear());
        return item;
    }

    @Transactional(readOnly = true)
    public java.util.Optional<AnnualTaxAdjustmentRequest> find(UUID employeeId, int taxYear) {
        return requests.findByEmployeeIdAndTaxYear(employeeId, taxYear);
    }

    @Transactional(readOnly = true)
    public AnnualTaxAdjustmentRequest read(UUID id) {
        return requests.findById(id).orElseThrow(() -> new NotFoundException("Annual tax adjustment", id));
    }

    private AnnualTaxAdjustmentRequest pending(UUID id) {
        AnnualTaxAdjustmentRequest item = requests.lockById(id)
                .orElseThrow(() -> new NotFoundException("Annual tax adjustment", id));
        if (!"PENDING".equals(item.getStatus()))
            throw new BusinessException("Annual tax request is already " + item.getStatus());
        return item;
    }

    private void validateYear(int year) {
        if (year < 2023 || year >= LocalDate.now().getYear())
            throw new BusinessException("Only complete prior tax years can be adjusted");
    }

    private PayPeriod verifyTarget(UUID periodId, int year) {
        PayPeriod period = periods.findById(periodId)
                .orElseThrow(() -> new NotFoundException("Pay period", periodId));
        if (!"OPEN".equals(period.getStatus()) ||
                period.getStartDate() == null ||
                !period.getStartDate().isAfter(LocalDate.of(year, 12, 31)))
            throw new BusinessException("Target must be an OPEN pay period after the tax year");
        return period;
    }
}
