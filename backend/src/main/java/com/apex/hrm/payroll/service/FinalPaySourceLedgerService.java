package com.apex.hrm.payroll.service;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Read-only comparison against persisted payroll/leave/loan sources.
 * Historical paid 13th-month amounts are NOT automatically the outstanding accrual.
 * Leave balances are DAYS, not money. No unsafe automatic payout certification.
 */
@Service
public class FinalPaySourceLedgerService {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public FinalPaySourceLedgerService(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record SourceReview(UUID settlementId,UUID payrollEmployeeId,int taxYear,
        int lockedPayrollLines,BigDecimal paidThirteenthMonth,BigDecimal payrollWithholding,
        BigDecimal outstandingActiveLoans,int activeLoanCount,
        BigDecimal convertibleLeaveDays,int convertibleLeaveEntries,
        BigDecimal declaredThirteenthMonth,BigDecimal declaredOutstandingLoans,
        BigDecimal declaredLeaveConversion,String payrollStatus,String loanStatus,
        String leaveStatus,String certificationStatus,boolean disbursementEnabled,
        List<String> requiredActions) {}
    private record Settlement(UUID id,UUID employeeId,UUID caseId,
        String status,BigDecimal thirteenth,BigDecimal loans,BigDecimal leave) {}
    private record Identity(UUID employeeId,String status) {}
    private record PayrollTotals(int count,BigDecimal thirteenth,BigDecimal withholding) {}
    private record Loans(int count,BigDecimal balance) {}
    private record Leaves(int count,BigDecimal days) {}

    public SourceReview review(HrmUserPrincipal user,UUID settlementId) {
        return tenants.inTenant(user.tenantId(),()-> {
            List<Settlement> ss=jdbc.query("""
                SELECT id,employee_id,case_id,status,accrued_thirteenth_month,
                       outstanding_loans,unused_leave_conversion
                FROM payroll_final_pay_settlement WHERE tenant_id=? AND id=?
                """,(rs,i)->new Settlement(rs.getObject("id",UUID.class),
                    rs.getObject("employee_id",UUID.class),rs.getObject("case_id",UUID.class),
                    rs.getString("status"),rs.getBigDecimal("accrued_thirteenth_month"),
                    rs.getBigDecimal("outstanding_loans"),rs.getBigDecimal("unused_leave_conversion")),
                    user.tenantId(),settlementId);
            if(ss.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Final-pay settlement not found");
            Settlement s=ss.get(0);
            List<Identity> links=jdbc.query("""
                SELECT payroll_employee_id,status FROM payroll_personnel_identity_link
                WHERE tenant_id=? AND personnel_employee_id=?
                """,(rs,i)->new Identity(rs.getObject(1,UUID.class),rs.getString(2)),
                    user.tenantId(),s.employeeId());
            if(links.size()!=1 || !"APPROVED".equals(links.get(0).status()))
                return new SourceReview(s.id(),null,LocalDate.now().getYear(),0,
                    BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,0,BigDecimal.ZERO,0,
                    s.thirteenth(),s.loans(),s.leave(),"IDENTITY_UNVERIFIED",
                    "IDENTITY_UNVERIFIED","IDENTITY_UNVERIFIED",
                    "REVIEW_REQUIRED",false,List.of("Approve the matching legacy payroll employee identity before querying financial ledgers"));
            UUID employee=links.get(0).employeeId();
            LocalDate effective=jdbc.queryForObject("""
                SELECT effective_date FROM personnel_hr_case
                WHERE tenant_id=? AND id=? AND employee_id=?
                """,LocalDate.class,user.tenantId(),s.caseId(),s.employeeId());
            if(effective==null)throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Separation effective date is missing");
            int year=effective.getYear();
            PayrollTotals payroll=jdbc.queryForObject("""
                SELECT count(*)::int,
                    COALESCE(sum(CASE WHEN l.item_code='THIRTEENTH_MONTH'
                        AND l.category='EARNING' THEN l.amount ELSE 0 END),0),
                    COALESCE(sum(CASE WHEN l.item_code IN ('WTAX','WTAX_ANNUAL')
                        AND l.category='DEDUCTION' THEN l.amount ELSE 0 END),0)
                FROM payroll_line l JOIN payroll_run r
                    ON r.tenant_id=l.tenant_id AND r.id=l.run_id
                JOIN pay_period p ON p.tenant_id=r.tenant_id AND p.id=r.pay_period_id
                WHERE l.tenant_id=? AND l.employee_id=?
                  AND r.status IN ('LOCKED','PAID')
                  AND p.date_from>=? AND p.date_to<=?
                """,(rs,i)->new PayrollTotals(rs.getInt(1),rs.getBigDecimal(2),rs.getBigDecimal(3)),
                    user.tenantId(),employee,LocalDate.of(year,1,1),effective);
            Loans loans=jdbc.queryForObject("""
                SELECT count(*)::int,COALESCE(sum(balance),0)
                FROM loan WHERE tenant_id=? AND employee_id=? AND status='ACTIVE'
                """,(rs,i)->new Loans(rs.getInt(1),rs.getBigDecimal(2)),user.tenantId(),employee);
            Leaves leave=jdbc.queryForObject("""
                SELECT count(*)::int,COALESCE(sum(l.days),0)
                FROM leave_ledger l JOIN leave_type t
                  ON t.tenant_id=l.tenant_id AND t.id=l.leave_type_id
                WHERE l.tenant_id=? AND l.employee_id=? AND t.convertible
                  AND l.entry_date<=?
                """,(rs,i)->new Leaves(rs.getInt(1),rs.getBigDecimal(2)),
                    user.tenantId(),employee,effective);

            String payrollStatus=payroll.count()==0?"PAYROLL_SOURCE_MISSING":"ANNUAL_COVERAGE_AND_ACCRUAL_REVIEW_REQUIRED";
            String loanStatus=loanStatus(loans.count(),loans.balance(),s.loans());
            String leaveStatus=leaveStatus(leave.count(),leave.days(),s.leave());
            List<String> actions=new java.util.ArrayList<>();
            actions.add("Recompute unpaid basic and 13th-month earned/accrued less previously paid benefits from complete locked payroll and hire/separation dates");
            actions.add("Revalidate BIR final withholding and all statutory deductions against approved government tables");
            if(!"MATCHED_ACTIVE_BALANCE_REVIEW_REQUIRED".equals(loanStatus))
                actions.add("Reconcile outstanding loan balances, deductions and recovery authorization");
            actions.add("Approve per-leave-type conversion rate, eligibility and unused days; days cannot be converted to pesos automatically");
            actions.add("Obtain independent HR/payroll/finance sign-off and bank account ownership verification");
            return new SourceReview(s.id(),employee,year,payroll.count(),payroll.thirteenth(),
                payroll.withholding(),loans.balance(),loans.count(),leave.days(),leave.count(),
                s.thirteenth(),s.loans(),s.leave(),payrollStatus,loanStatus,leaveStatus,
                "REVIEW_REQUIRED",false,List.copyOf(actions));
        });
    }

    static String loanStatus(int count,BigDecimal ledger,BigDecimal claimed) {
        if(ledger==null || claimed==null || ledger.signum()<0 || claimed.signum()<0)
            return "LOAN_INVALID";
        if(count==0) return claimed.signum()==0?"NO_ACTIVE_LOANS_REVIEW_REQUIRED":"LOAN_SOURCE_MISSING";
        return ledger.compareTo(claimed)==0?
            "MATCHED_ACTIVE_BALANCE_REVIEW_REQUIRED":"LOAN_BALANCE_MISMATCH";
    }
    static String leaveStatus(int count,BigDecimal days,BigDecimal monetaryClaim) {
        if(days==null || monetaryClaim==null || days.signum()<0)
            return "LEAVE_LEDGER_INVALID";
        if(count==0) return monetaryClaim.signum()==0?"NO_CONVERTIBLE_LEAVE_REVIEW_REQUIRED":"LEAVE_SOURCE_MISSING";
        return "CONVERSION_RATE_AND_ELIGIBILITY_REVIEW_REQUIRED";
    }
}
