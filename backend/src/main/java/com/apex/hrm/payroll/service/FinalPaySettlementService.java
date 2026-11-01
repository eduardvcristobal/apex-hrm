package com.apex.hrm.payroll.service;

import com.apex.hrm.payroll.engine.FinalPayCalculator;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Final-pay approval record, not a disbursement. Personnel identifiers are not yet
 * verified against legacy payroll employee IDs, so payout remains blocked.
 */
@Service
public class FinalPaySettlementService {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;

    public FinalPaySettlementService(JdbcTemplate jdbc, TenantJdbcExecutor tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    public record Submission(FinalPayCalculator.Input amounts, String accountingReference,
                             String justification, boolean leaveVerified,
                             boolean loansVerified, boolean taxVerified,
                             boolean separationEntitlementVerified) {}
    public record Settlement(UUID id, UUID handoffId, UUID employeeId, UUID caseId,
                             String status, BigDecimal gross, BigDecimal deductions,
                             BigDecimal net, UUID requestedBy, UUID decidedBy,
                             String settlementGate) {}

    public Settlement submit(HrmUserPrincipal user, UUID employeeId, UUID caseId,
                             UUID handoffId, Submission request) {
        if (request == null || request.amounts() == null || request.accountingReference() == null
                || request.justification() == null || request.accountingReference().isBlank()
                || request.accountingReference().length() > 1000
                || request.justification().trim().length() < 20
                || request.justification().length() > 2000
                || !request.leaveVerified() || !request.loansVerified()
                || !request.taxVerified() || !request.separationEntitlementVerified())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Verified final-pay source references, review attestations and justification are required");
        FinalPayCalculator.Result calculated = FinalPayCalculator.preview(request.amounts());
        if (calculated.net().signum() < 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Negative final pay requires separate recovery review");
        return tenants.inTenant(user.tenantId(), () -> {
            validateCaseAndClearance(user.tenantId(), employeeId, caseId, handoffId);
            FinalPayCalculator.Input a = request.amounts();
            List<Settlement> created = jdbc.query("""
                INSERT INTO payroll_final_pay_settlement
                (tenant_id,handoff_id,employee_id,case_id,unpaid_basic,accrued_thirteenth_month,
                unused_leave_conversion,separation_pay,other_earnings,outstanding_loans,
                accountable_property,withholding_tax,other_authorized_deductions,gross,
                deductions,net,accounting_reference,justification,requested_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id,handoff_id) DO NOTHING
                RETURNING id,handoff_id,employee_id,case_id,status,gross,deductions,net,requested_by,approved_by
                """, (rs, i) -> map(rs), user.tenantId(), handoffId, employeeId, caseId,
                a.unpaidBasic(), a.accruedThirteenthMonth(), a.unusedLeaveConversion(),
                a.separationPay(), a.otherEarnings(), a.outstandingLoans(),
                a.accountableProperty(), a.withholdingTax(), a.otherAuthorizedDeductions(),
                calculated.gross(), calculated.deductions(), calculated.net(),
                request.accountingReference().trim(), request.justification().trim(), user.id());
            if (created.isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Final-pay settlement already exists for this handoff");
            audit(user, "FINAL_PAY_SETTLEMENT_REQUESTED", created.get(0).id());
            return created.get(0);
        });
    }

    public Settlement decide(HrmUserPrincipal user, UUID settlementId, boolean approve) {
        return tenants.inTenant(user.tenantId(), () -> {
            List<Settlement> pending = jdbc.query("""
                SELECT id,handoff_id,employee_id,case_id,status,gross,deductions,net,requested_by,approved_by
                FROM payroll_final_pay_settlement WHERE tenant_id=? AND id=? FOR UPDATE
                """, (rs,i)->map(rs), user.tenantId(), settlementId);
            if (pending.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Settlement not found");
            Settlement item = pending.get(0);
            if (!"PENDING".equals(item.status()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Settlement already decided");
            if (user.id().equals(item.requestedBy()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Independent settlement approval required");
            validateCaseAndClearance(user.tenantId(), item.employeeId(), item.caseId(), item.handoffId());
            Settlement decided = jdbc.queryForObject("""
                UPDATE payroll_final_pay_settlement SET status=?,approved_by=?,approved_at=now(),updated_at=now()
                WHERE tenant_id=? AND id=? AND status='PENDING'
                RETURNING id,handoff_id,employee_id,case_id,status,gross,deductions,net,requested_by,approved_by
                """, (rs,i)->map(rs), approve ? "APPROVED" : "REJECTED", user.id(),
                user.tenantId(), item.id());
            audit(user, approve ? "FINAL_PAY_SETTLEMENT_APPROVED" : "FINAL_PAY_SETTLEMENT_REJECTED", item.id());
            return decided;
        });
    }

    /** Itemized finance/HR review statement; no payslip or bank disbursement is generated. */
    public record Statement(UUID settlementId, UUID employeeId, UUID caseId, String status,
            BigDecimal unpaidBasic, BigDecimal accruedThirteenthMonth,
            BigDecimal unusedLeaveConversion, BigDecimal separationPay,
            BigDecimal otherEarnings, BigDecimal outstandingLoans,
            BigDecimal accountableProperty, BigDecimal withholdingTax,
            BigDecimal otherAuthorizedDeductions, BigDecimal gross,
            BigDecimal deductions, BigDecimal net, String accountingReference,
            String justification, String disbursementStatus) {}

    public Statement statement(HrmUserPrincipal user, UUID id) {
        return tenants.inTenant(user.tenantId(), () -> {
            List<Statement> rows = jdbc.query("""
                SELECT * FROM payroll_final_pay_settlement WHERE tenant_id=? AND id=?
                """, (rs,i) -> new Statement(
                    rs.getObject("id",UUID.class), rs.getObject("employee_id",UUID.class),
                    rs.getObject("case_id",UUID.class), rs.getString("status"),
                    rs.getBigDecimal("unpaid_basic"), rs.getBigDecimal("accrued_thirteenth_month"),
                    rs.getBigDecimal("unused_leave_conversion"),rs.getBigDecimal("separation_pay"),
                    rs.getBigDecimal("other_earnings"),rs.getBigDecimal("outstanding_loans"),
                    rs.getBigDecimal("accountable_property"),rs.getBigDecimal("withholding_tax"),
                    rs.getBigDecimal("other_authorized_deductions"),rs.getBigDecimal("gross"),
                    rs.getBigDecimal("deductions"),rs.getBigDecimal("net"),
                    rs.getString("accounting_reference"),rs.getString("justification"),
                    "BLOCKED: no certified personnel-to-payroll mapping, no payslip and no disbursement"),
                    user.tenantId(),id);
            if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Settlement not found");
            return rows.get(0);
        });
    }

    public List<Settlement> list(HrmUserPrincipal user, UUID employeeId, UUID caseId) {
        return tenants.inTenant(user.tenantId(), () -> jdbc.query("""
            SELECT id,handoff_id,employee_id,case_id,status,gross,deductions,net,requested_by,approved_by
            FROM payroll_final_pay_settlement WHERE tenant_id=? AND employee_id=? AND case_id=?
            ORDER BY created_at DESC LIMIT 100
            """, (rs,i)->map(rs),user.tenantId(),employeeId,caseId));
    }

    private void validateCaseAndClearance(UUID tenant, UUID employee, UUID caseId, UUID handoffId) {
        List<String> approvals = jdbc.query("""
            SELECT c.status FROM personnel_hr_case c
            JOIN personnel_final_pay_handoff h ON h.tenant_id=c.tenant_id
                AND h.hr_case_id=c.id AND h.employee_id=c.employee_id
            WHERE c.tenant_id=? AND c.id=? AND c.employee_id=? AND h.id=?
                AND c.case_type='SEPARATION' AND c.effective_date<=?
            FOR UPDATE OF c
            """, (rs,i)->rs.getString(1), tenant, caseId, employee, handoffId, LocalDate.now());
        if (approvals.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Approved separation handoff not found");
        if (!"APPROVED".equals(approvals.get(0)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Separation case must be approved");
        Integer required = jdbc.queryForObject("""
            SELECT count(*) FROM personnel_separation_clearance
            WHERE tenant_id=? AND hr_case_id=? AND required
            """,Integer.class,tenant,caseId);
        Integer incomplete = jdbc.queryForObject("""
            SELECT count(*) FROM personnel_separation_clearance
            WHERE tenant_id=? AND hr_case_id=? AND required AND status<>'CLEARED'
            """,Integer.class,tenant,caseId);
        Integer assets = jdbc.queryForObject("""
            SELECT count(*) FROM personnel_separation_asset
            WHERE tenant_id=? AND hr_case_id=? AND status<>'RETURNED'
            """,Integer.class,tenant,caseId);
        if (required == null || required == 0 || incomplete == null || incomplete > 0
                || assets == null || assets > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Exit clearance or asset reconciliation is incomplete");
    }

    private void audit(HrmUserPrincipal user, String action, UUID id) {
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES (?,?,?,'payroll_final_pay_settlement',?)
            """, user.tenantId(),user.id(),action,id);
    }

    private static Settlement map(ResultSet rs) throws SQLException {
        return new Settlement(rs.getObject("id",UUID.class),
                rs.getObject("handoff_id",UUID.class),rs.getObject("employee_id",UUID.class),
                rs.getObject("case_id",UUID.class),rs.getString("status"),
                rs.getBigDecimal("gross"),rs.getBigDecimal("deductions"),rs.getBigDecimal("net"),
                rs.getObject("requested_by",UUID.class),rs.getObject("approved_by",UUID.class),
                "NO_DISBURSEMENT: personnel-to-legacy-payroll employee mapping requires certification");
    }
}
