package com.apex.hrm.payroll.service;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.payroll.domain.Adjustment;
import com.apex.hrm.payroll.domain.PayPeriod;
import com.apex.hrm.payroll.repo.AdjustmentRepository;
import com.apex.hrm.payroll.repo.PayPeriodRepository;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.platform.service.AuditService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Corrections never edit a closed run: they become approved adjustments that flow into a later run. */
@Service
public class AdjustmentService {
    private final AdjustmentRepository adjustments;
    private final PayPeriodRepository periods;
    private final EmployeeRepository employees;
    private final AuditService audit;

    public AdjustmentService(AdjustmentRepository adjustments, PayPeriodRepository periods, EmployeeRepository employees,
                             AuditService audit) {
        this.adjustments = adjustments;
        this.periods = periods;
        this.employees = employees;
        this.audit = audit;
    }

    @Transactional
    public Adjustment create(UUID employeeId, String itemCode, String description, String category, BigDecimal amount,
                             boolean taxable, String reason, UUID sourcePeriodId, UUID targetPeriodId, UUID requestedBy) {
        employees.findById(employeeId).orElseThrow(() -> new NotFoundException("Employee", employeeId));
        if (!"EARNING".equals(category) && !"DEDUCTION".equals(category)) {
            throw new BusinessException("category must be EARNING or DEDUCTION");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("amount must be positive; use category to express direction");
        }
        if (reason == null || reason.isBlank() || itemCode == null || itemCode.isBlank()) {
            throw new BusinessException("itemCode and reason are required");
        }
        if (targetPeriodId != null) {
            PayPeriod target = periods.findById(targetPeriodId).orElseThrow(() -> new NotFoundException("Pay period", targetPeriodId));
            if ("LOCKED".equals(target.getStatus())) {
                throw new BusinessException("Target pay period is locked; choose an open period");
            }
        }
        Adjustment a = new Adjustment();
        a.setEmployeeId(employeeId);
        a.setItemCode(itemCode);
        a.setDescription(description);
        a.setCategory(category);
        a.setAmount(amount);
        a.setTaxable(taxable);
        a.setReason(reason);
        a.setSourcePeriodId(sourcePeriodId);
        a.setTargetPeriodId(targetPeriodId);
        a.setRequestedBy(requestedBy);
        a = adjustments.save(a);
        audit.log("Adjustment", a.getId(), "CREATE", category + " " + amount + " " + itemCode);
        return a;
    }

    @Transactional
    public Adjustment approve(UUID id, UUID approverId) {
        Adjustment a = pending(id);
        if (approverId != null && approverId.equals(a.getRequestedBy())) {
            throw new BusinessException("The requester cannot approve their own adjustment");
        }
        a.setStatus("APPROVED");
        a.setApprovedBy(approverId);
        a.setApprovedAt(Instant.now());
        audit.log("Adjustment", id, "APPROVE", "approved by " + approverId);
        return adjustments.save(a);
    }

    @Transactional
    public Adjustment reject(UUID id, UUID approverId) {
        Adjustment a = pending(id);
        a.setStatus("REJECTED");
        a.setApprovedBy(approverId);
        a.setApprovedAt(Instant.now());
        audit.log("Adjustment", id, "REJECT", "rejected by " + approverId);
        return adjustments.save(a);
    }

    private Adjustment pending(UUID id) {
        Adjustment a = adjustments.findById(id).orElseThrow(() -> new NotFoundException("Adjustment", id));
        if (!"PENDING".equals(a.getStatus())) {
            throw new BusinessException("Adjustment is already " + a.getStatus());
        }
        return a;
    }
}
