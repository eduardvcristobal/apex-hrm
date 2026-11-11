package com.apex.hrm.payroll.service;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.approvals.ApprovalWorkflowService;
import com.apex.hrm.payroll.domain.Adjustment;
import com.apex.hrm.payroll.domain.PayPeriod;
import com.apex.hrm.payroll.repo.AdjustmentRepository;
import com.apex.hrm.payroll.repo.PayPeriodRepository;
import com.apex.hrm.personnel.repo.EmploymentRecordRepository;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.platform.service.AuditService;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Corrections never edit a closed run: they become approved adjustments that flow into a later run. */
@Service
public class AdjustmentService {
    private final AdjustmentRepository adjustments;
    private final PayPeriodRepository periods;
    private final EmployeeRepository employees;
    private final EmploymentRecordRepository employment;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    private final ApprovalWorkflowService approvals;

    public AdjustmentService(AdjustmentRepository adjustments, PayPeriodRepository periods, EmployeeRepository employees,
                             EmploymentRecordRepository employment, AuditService audit, JdbcTemplate jdbc,
                             TenantJdbcExecutor tenants, ApprovalWorkflowService approvals) {
        this.adjustments = adjustments;
        this.periods = periods;
        this.employees = employees;
        this.employment = employment;
        this.audit = audit;
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.approvals = approvals;
    }

    @Transactional
    public Adjustment create(UUID tenantId, UUID employeeId, String itemCode, String description, String category,
                             BigDecimal amount, boolean taxable, String reason, UUID sourcePeriodId,
                             UUID targetPeriodId, String incomeClass, UUID requestedBy) {
        var employee = employees.findById(employeeId).orElseThrow(() -> new NotFoundException("Employee", employeeId));
        if (!"EARNING".equals(category) && !"DEDUCTION".equals(category)) {
            throw new BusinessException("category must be EARNING or DEDUCTION");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("amount must be positive; use category to express direction");
        }
        if (reason == null || reason.isBlank() || itemCode == null || !itemCode.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,39}")) {
            throw new BusinessException("A valid payroll item code and business reason are required");
        }
        if (description == null || description.isBlank() || description.length() > 255) {
            throw new BusinessException("Description is required and must be 255 characters or fewer");
        }
        if (requestedBy == null) {
            throw new BusinessException("Authenticated requester is required");
        }
        if (targetPeriodId == null) {
            throw new BusinessException("Choose an open target payroll period for this adjustment");
        }
        PayPeriod target = periods.findById(targetPeriodId).orElseThrow(() -> new NotFoundException("Pay period", targetPeriodId));
        if (!"OPEN".equals(target.getStatus())) {
            throw new BusinessException("Target pay period is not open; choose another period");
        }
        if (employee.getSeparationDate() != null && employee.getSeparationDate().isBefore(target.getStartDate())) {
            throw new BusinessException("Target payroll period is after the employee's separation date");
        }
        boolean targetMatchesEmployee = employment.findActiveInPayGroup(target.getPayGroupId(), target.getStartDate(), target.getEndDate())
                .stream().anyMatch(record -> record.getEmployeeId().equals(employeeId));
        if (!targetMatchesEmployee) {
            throw new BusinessException("Target pay period must belong to the employee's pay group");
        }
        if (sourcePeriodId != null) {
            PayPeriod source = periods.findById(sourcePeriodId).orElseThrow(() -> new NotFoundException("Source pay period", sourcePeriodId));
            if (!"LOCKED".equals(source.getStatus())) {
                throw new BusinessException("The source pay period must be locked to use it as a payroll correction reference");
            }
            if (!target.getStartDate().isAfter(source.getEndDate())) {
                throw new BusinessException("The target payroll period must start after the source pay period ends");
            }
            boolean sourceMatchesEmployee = employment.findActiveInPayGroup(source.getPayGroupId(), source.getStartDate(), source.getEndDate())
                    .stream().anyMatch(record -> record.getEmployeeId().equals(employeeId));
            if (!sourceMatchesEmployee) {
                throw new BusinessException("Source pay period must belong to the employee's pay group at that time");
            }
        }
        if (incomeClass == null || incomeClass.isBlank()) incomeClass = "SUPPLEMENTARY";
        if (!"REGULAR".equals(incomeClass) && !"SUPPLEMENTARY".equals(incomeClass)) {
            throw new BusinessException("incomeClass must be REGULAR or SUPPLEMENTARY");
        }
        Adjustment a = new Adjustment();
        a.setEmployeeId(employeeId);
        a.setItemCode(itemCode.trim().toUpperCase(java.util.Locale.ROOT));
        a.setDescription(description.trim());
        a.setCategory(category);
        a.setAmount(amount);
        a.setTaxable("EARNING".equals(category) && taxable);
        a.setIncomeClass("EARNING".equals(category) ? incomeClass : "SUPPLEMENTARY");
        a.setReason(reason.trim());
        a.setSourcePeriodId(sourcePeriodId);
        a.setTargetPeriodId(targetPeriodId);
        a.setRequestedBy(requestedBy);
        a = adjustments.save(a);
        UUID personnelEmployeeId = tenants.inTenant(tenantId, () -> jdbc.query(
                "SELECT personnel_employee_id FROM personnel_timekeeping_employee WHERE tenant_id=? AND employee_id=?",
                (rs, row) -> rs.getObject(1, UUID.class), tenantId, employeeId).stream().findFirst()
                .orElseThrow(() -> new BusinessException("The employee is not linked to the personnel reporting structure")));
        UUID approvalId = approvals.start(tenantId, requestedBy, personnelEmployeeId, "PAYROLL_ADJUSTMENT", "adjustment",
                a.getId(), a.getCategory() + " " + a.getItemCode() + " " + a.getAmount() + " for " + a.getDescription());
        a.setApprovalInstanceId(approvalId);
        a = adjustments.save(a);
        audit.log("Adjustment", a.getId(), "CREATE", category + " " + amount + " " + itemCode);
        return a;
    }

    @Transactional
    public Adjustment approve(UUID id, UUID approverId) {
        Adjustment a = pending(id);
        if (a.getApprovalInstanceId() != null) {
            throw new BusinessException("This adjustment uses its configured approval workflow. Decide it in the Approval Center.");
        }
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
        if (a.getApprovalInstanceId() != null) {
            throw new BusinessException("This adjustment uses its configured approval workflow. Decide it in the Approval Center.");
        }
        if (approverId != null && approverId.equals(a.getRequestedBy())) {
            throw new BusinessException("The requester cannot decide their own adjustment");
        }
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
