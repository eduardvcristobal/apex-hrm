package com.apex.hrm.payroll.service;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Produces an independently authorized payment *instruction*, never submits it to a bank.
 * External remittance confirmation and reconciliation are separate, future gates.
 */
@Service
public class FinalPayPaymentAuthorizationService {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;

    public FinalPayPaymentAuthorizationService(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }

    public record Prepare(UUID settlementId,UUID bankAccountId,
                          String sourceReconciliationReference) {}
    public record Authorization(UUID id,UUID settlementId,UUID identityLinkId,
       UUID bankAccountId,BigDecimal amount,String status,UUID preparedBy,UUID authorizedBy,
       String bankTransmissionStatus) {}
    private record SettlementData(UUID id,UUID personnelId,String status,BigDecimal net) {}
    private record IdentityData(UUID id,UUID payrollId,String status) {}
    private record AccountData(UUID id,UUID employeeId,boolean primaryAccount) {}

    public Authorization prepare(HrmUserPrincipal user,Prepare request) {
        if(request==null || request.settlementId()==null || request.bankAccountId()==null ||
                request.sourceReconciliationReference()==null ||
                request.sourceReconciliationReference().trim().length()<20 ||
                request.sourceReconciliationReference().length()>1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Settlement, bank record and signed financial reconciliation reference required");
        return tenants.inTenant(user.tenantId(),()->{
            SettlementData settlement=loadSettlement(user.tenantId(),request.settlementId());
            IdentityData identity=loadIdentity(user.tenantId(),settlement.personnelId());
            AccountData account=loadAccount(user.tenantId(),request.bankAccountId());
            requireReady(settlement.status(),settlement.net(),identity.status(),
                identity.payrollId(),account.employeeId(),account.primaryAccount());
            List<Authorization> rows=jdbc.query("""
                INSERT INTO payroll_final_pay_payment_authorization
                  (tenant_id,settlement_id,identity_link_id,bank_account_id,amount,
                   source_reconciliation_reference,prepared_by)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id,settlement_id) DO NOTHING
                RETURNING id,settlement_id,identity_link_id,bank_account_id,amount,status,prepared_by,authorized_by
                """,(rs,i)->map(rs),user.tenantId(),settlement.id(),identity.id(),account.id(),
                settlement.net(),request.sourceReconciliationReference().trim(),user.id());
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Payment authorization already exists for this settlement");
            audit(user,"FINAL_PAY_PAYMENT_PREPARED",rows.get(0).id());
            return rows.get(0);
        });
    }

    public Authorization decide(HrmUserPrincipal user,UUID id,boolean approve) {
        return tenants.inTenant(user.tenantId(),()->{
            List<Authorization> rows=jdbc.query("""
                SELECT id,settlement_id,identity_link_id,bank_account_id,amount,status,prepared_by,authorized_by
                FROM payroll_final_pay_payment_authorization WHERE tenant_id=? AND id=? FOR UPDATE
                """,(rs,i)->map(rs),user.tenantId(),id);
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment authorization not found");
            Authorization pending=rows.get(0);
            requireIndependentDecision(pending.status(),pending.preparedBy(),user.id());
            if(approve){
                SettlementData settlement=loadSettlement(user.tenantId(),pending.settlementId());
                IdentityData identity=loadIdentity(user.tenantId(),settlement.personnelId());
                AccountData account=loadAccount(user.tenantId(),pending.bankAccountId());
                requireReady(settlement.status(),settlement.net(),identity.status(),
                    identity.payrollId(),account.employeeId(),account.primaryAccount());
                if(!identity.id().equals(pending.identityLinkId()) ||
                        !settlement.net().equals(pending.amount()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Mapped payroll identity or net settlement changed since preparation");
            }
            Authorization result=jdbc.queryForObject("""
                UPDATE payroll_final_pay_payment_authorization SET status=?,authorized_by=?,authorized_at=now()
                WHERE tenant_id=? AND id=? AND status='PREPARED'
                RETURNING id,settlement_id,identity_link_id,bank_account_id,amount,status,prepared_by,authorized_by
                """,(rs,i)->map(rs),approve?"AUTHORIZED":"CANCELLED",user.id(),user.tenantId(),id);
            audit(user,approve?"FINAL_PAY_PAYMENT_AUTHORIZED":"FINAL_PAY_PAYMENT_CANCELLED",id);
            return result;
        });
    }

    public record EligibleBankAccount(UUID id,String bankCode,String maskedAccount) {}

    /** Never returns an account number; lists only primary accounts of an approved identity link. */
    public List<EligibleBankAccount> eligibleAccounts(HrmUserPrincipal user,UUID personnelEmployeeId) {
        return tenants.inTenant(user.tenantId(),()->{
            IdentityData identity=loadIdentity(user.tenantId(),personnelEmployeeId);
            if(!"APPROVED".equals(identity.status()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Employee identity must be independently approved");
            return jdbc.query("""
                SELECT id,bank_code,right(account_no,4) AS last_four
                FROM employee_bank_account
                WHERE tenant_id=? AND employee_id=? AND primary_account=true
                ORDER BY id LIMIT 20
                """,(rs,i)->new EligibleBankAccount(rs.getObject("id",UUID.class),
                    rs.getString("bank_code"),"••••"+rs.getString("last_four")),
                user.tenantId(),identity.payrollId());
        });
    }

    public List<Authorization> list(HrmUserPrincipal user,UUID settlementId) {
        return tenants.inTenant(user.tenantId(),()->jdbc.query("""
            SELECT id,settlement_id,identity_link_id,bank_account_id,amount,status,prepared_by,authorized_by
            FROM payroll_final_pay_payment_authorization WHERE tenant_id=? AND settlement_id=?
            """,(rs,i)->map(rs),user.tenantId(),settlementId));
    }

    static void requireIndependentDecision(String status,UUID preparer,UUID approver) {
        if(!"PREPARED".equals(status))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Payment instruction already decided");
        if(approver==null || approver.equals(preparer))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Payment authorization requires an independent approver");
    }

    static void requireReady(String settlementStatus,BigDecimal net,String linkStatus,
                             UUID linkedPayrollEmployee,UUID bankEmployee,boolean primary) {
        if(!"APPROVED".equals(settlementStatus) || net==null || net.signum()<=0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A positive independently approved final pay settlement is required");
        if(!"APPROVED".equals(linkStatus) || !linkedPayrollEmployee.equals(bankEmployee))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Verified payroll identity and selected bank account do not match");
        if(!primary)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only a verified primary employee bank account can be selected");
    }

    private SettlementData loadSettlement(UUID tenant,UUID id) {
        List<SettlementData> rows=jdbc.query("""
            SELECT id,employee_id,status,net FROM payroll_final_pay_settlement
            WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,i)->new SettlementData(rs.getObject("id",UUID.class),
                rs.getObject("employee_id",UUID.class),rs.getString("status"),
                rs.getBigDecimal("net")),tenant,id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Final pay settlement not found");
        return rows.get(0);
    }

    private IdentityData loadIdentity(UUID tenant,UUID personnelId) {
        List<IdentityData> rows=jdbc.query("""
            SELECT id,payroll_employee_id,status FROM payroll_personnel_identity_link
            WHERE tenant_id=? AND personnel_employee_id=? FOR UPDATE
            """,(rs,i)->new IdentityData(rs.getObject("id",UUID.class),
                rs.getObject("payroll_employee_id",UUID.class),rs.getString("status")),tenant,personnelId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Personnel-to-payroll identity must be independently linked");
        return rows.get(0);
    }

    private AccountData loadAccount(UUID tenant,UUID accountId) {
        List<AccountData> rows=jdbc.query("""
            SELECT id,employee_id,primary_account FROM employee_bank_account
            WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,i)->new AccountData(rs.getObject("id",UUID.class),
                rs.getObject("employee_id",UUID.class),rs.getBoolean("primary_account")),tenant,accountId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Bank account not found");
        return rows.get(0);
    }

    private void audit(HrmUserPrincipal user,String action,UUID entityId) {
        jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,?,'payroll_final_pay_payment_authorization',?)",
            user.tenantId(),user.id(),action,entityId);
    }
    private static Authorization map(ResultSet rs) throws SQLException {
        return new Authorization(rs.getObject("id",UUID.class),
            rs.getObject("settlement_id",UUID.class),rs.getObject("identity_link_id",UUID.class),
            rs.getObject("bank_account_id",UUID.class),rs.getBigDecimal("amount"),
            rs.getString("status"),rs.getObject("prepared_by",UUID.class),
            rs.getObject("authorized_by",UUID.class),
            "NOT_SENT: authorization only; bank transmission and reconciliation are not implemented");
    }
}
