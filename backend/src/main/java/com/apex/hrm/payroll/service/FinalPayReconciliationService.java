package com.apex.hrm.payroll.service;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only final-pay reconciliation preflight.
 * A reviewed external reference is never treated as actual bank confirmation.
 * No transfer, payslip issuance, payroll posting or PAID status mutation exists here.
 */
@Service
public class FinalPayReconciliationService {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;

    public FinalPayReconciliationService(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }

    public record Preflight(UUID settlementId,UUID personnelEmployeeId,String settlementStatus,
        UUID paymentAuthorizationId,String paymentStatus,BigDecimal settlementNet,
        BigDecimal authorizedAmount,String bankEvidenceStatus,BigDecimal reportedAmount,
        String controlStatus,boolean internallyConsistent,boolean bankConfirmed,
        boolean disbursementEnabled,String explanation) {}

    private record Snapshot(UUID settlementId,UUID employeeId,String settlementStatus,
        BigDecimal settlementNet,UUID paymentId,String paymentStatus,BigDecimal amount,
        String evidenceStatus,BigDecimal reportedAmount,String identityStatus,
        UUID linkedPayrollEmployee,UUID bankPayrollEmployee,boolean primaryBankAccount) {}

    public Preflight preflight(HrmUserPrincipal user,UUID settlementId) {
        return tenants.inTenant(user.tenantId(),()->{
            List<Snapshot> rows=jdbc.query("""
                SELECT s.id AS settlement_id,s.employee_id,s.status AS settlement_status,s.net,
                       p.id AS payment_id,p.status AS payment_status,p.amount,
                       e.status AS evidence_status,e.reported_amount,
                       l.status AS identity_status,l.payroll_employee_id AS mapped_employee,
                       ba.employee_id AS bank_employee,ba.primary_account
                FROM payroll_final_pay_settlement s
                LEFT JOIN payroll_final_pay_payment_authorization p
                    ON p.tenant_id=s.tenant_id AND p.settlement_id=s.id
                LEFT JOIN payroll_personnel_identity_link l
                    ON l.tenant_id=s.tenant_id AND l.id=p.identity_link_id
                LEFT JOIN employee_bank_account ba
                    ON ba.tenant_id=s.tenant_id AND ba.id=p.bank_account_id
                LEFT JOIN payroll_final_pay_bank_evidence e
                    ON e.tenant_id=s.tenant_id AND e.payment_authorization_id=p.id
                WHERE s.tenant_id=? AND s.id=?
                """,(rs,i)->new Snapshot(rs.getObject("settlement_id",UUID.class),
                    rs.getObject("employee_id",UUID.class),rs.getString("settlement_status"),
                    rs.getBigDecimal("net"),rs.getObject("payment_id",UUID.class),
                    rs.getString("payment_status"),rs.getBigDecimal("amount"),
                    rs.getString("evidence_status"),rs.getBigDecimal("reported_amount"),
                    rs.getString("identity_status"),rs.getObject("mapped_employee",UUID.class),
                    rs.getObject("bank_employee",UUID.class),rs.getBoolean("primary_account")),
                user.tenantId(),settlementId);
            if(rows.isEmpty())
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Final-pay settlement not found");
            Snapshot s=rows.get(0);
            String control=classify(s.settlementStatus(),s.settlementNet(),s.paymentStatus(),
                s.amount(),s.identityStatus(),s.linkedPayrollEmployee(),s.bankPayrollEmployee(),
                s.primaryBankAccount(),s.evidenceStatus(),s.reportedAmount());
            boolean internallyConsistent="MANUAL_EVIDENCE_REVIEWED".equals(control);
            return new Preflight(s.settlementId(),s.employeeId(),s.settlementStatus(),s.paymentId(),
                s.paymentStatus(),s.settlementNet(),s.amount(),s.evidenceStatus(),s.reportedAmount(),
                control,internallyConsistent,false,false,
                internallyConsistent ? "Internal review is consistent; bank acknowledgement and signed statement reconciliation remain outstanding"
                    : "Final pay not certified: "+control+"; no bank transmission or payment posting");
        });
    }

    static String classify(String settlement,BigDecimal net,String payment,
        BigDecimal amount,String identity,UUID mapped,UUID bankEmployee,
        boolean primary,String evidence,BigDecimal reported) {
        if(!"APPROVED".equals(settlement) || net==null || net.signum()<=0)
            return "SETTLEMENT_NOT_APPROVED";
        if(payment==null) return "PAYMENT_NOT_PREPARED";
        if(amount==null || amount.compareTo(net)!=0 || !"APPROVED".equals(identity)
            || mapped==null || !mapped.equals(bankEmployee) || !primary)
            return "IDENTITY_OR_AMOUNT_MISMATCH";
        if(!"AUTHORIZED".equals(payment)) return "PAYMENT_NOT_AUTHORIZED";
        if(evidence==null) return "BANK_EVIDENCE_MISSING";
        if(reported==null || reported.compareTo(amount)!=0) return "BANK_EVIDENCE_AMOUNT_MISMATCH";
        if(!"EVIDENCE_REVIEWED".equals(evidence)) return "BANK_EVIDENCE_NOT_APPROVED";
        return "MANUAL_EVIDENCE_REVIEWED";
    }
}
