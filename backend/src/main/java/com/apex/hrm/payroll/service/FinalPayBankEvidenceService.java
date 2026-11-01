package com.apex.hrm.payroll.service;

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

/** Separately records and reviews bank-provided evidence. This is NOT bank API reconciliation.
 * Marking evidence reviewed never changes a payroll run to PAID.
 */
@Service
public class FinalPayBankEvidenceService {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;

    public FinalPayBankEvidenceService(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record Submission(UUID paymentAuthorizationId,String bankCode,String bankReference,
       LocalDate bankValueDate,String evidenceDocumentReference,BigDecimal reportedAmount) {}
    public record Evidence(UUID id,UUID paymentAuthorizationId,String bankCode,
       String bankReference,LocalDate bankValueDate,BigDecimal reportedAmount,String status,
       UUID recordedBy,UUID reviewedBy,String verificationType) {}
    private record Payment(UUID id,String status,BigDecimal amount,UUID preparedBy,UUID authorizedBy) {}

    public Evidence submit(HrmUserPrincipal user,Submission input) {
        if(input==null || input.paymentAuthorizationId()==null ||
                input.bankCode()==null || input.bankCode().isBlank() || input.bankCode().length()>50 ||
                input.bankReference()==null || input.bankReference().trim().length()<6 ||
                input.bankReference().length()>160 || input.bankValueDate()==null ||
                input.bankValueDate().isAfter(LocalDate.now()) ||
                input.evidenceDocumentReference()==null ||
                input.evidenceDocumentReference().trim().length()<20 ||
                input.evidenceDocumentReference().length()>1000 ||
                input.reportedAmount()==null || input.reportedAmount().signum()<=0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Complete bank evidence reference, value date and positive amount are required");
        return tenants.inTenant(user.tenantId(),()->{
            Payment p=payment(user.tenantId(),input.paymentAuthorizationId());
            verifyAuthorizedPayment(p.status(),p.amount(),input.reportedAmount());
            List<Evidence> rows=jdbc.query("""
                INSERT INTO payroll_final_pay_bank_evidence
                 (tenant_id,payment_authorization_id,bank_code,bank_reference,bank_value_date,
                  evidence_document_reference,reported_amount,recorded_by)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT DO NOTHING
                RETURNING id,payment_authorization_id,bank_code,bank_reference,bank_value_date,
                    reported_amount,status,recorded_by,reviewed_by
                """,(rs,i)->map(rs),user.tenantId(),input.paymentAuthorizationId(),
                input.bankCode().trim().toUpperCase(java.util.Locale.ROOT),
                input.bankReference().trim(),input.bankValueDate(),
                input.evidenceDocumentReference().trim(),input.reportedAmount(),user.id());
            if(rows.isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Duplicate bank reference or already recorded payment evidence");
            audit(user,"FINAL_PAY_BANK_EVIDENCE_SUBMITTED",rows.get(0).id());
            return rows.get(0);
        });
    }

    public Evidence decide(HrmUserPrincipal user,UUID id,boolean approve) {
        return tenants.inTenant(user.tenantId(),()->{
            List<Evidence> entries=jdbc.query("""
                SELECT id,payment_authorization_id,bank_code,bank_reference,bank_value_date,
                    reported_amount,status,recorded_by,reviewed_by
                FROM payroll_final_pay_bank_evidence WHERE tenant_id=? AND id=? FOR UPDATE
                """,(rs,i)->map(rs),user.tenantId(),id);
            if(entries.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Evidence not found");
            Evidence row=entries.get(0);
            Payment p=payment(user.tenantId(),row.paymentAuthorizationId());
            requireIndependentEvidenceReview(row.status(),row.recordedBy(),p.preparedBy(),p.authorizedBy(),user.id());
            if(approve) verifyAuthorizedPayment(p.status(),p.amount(),row.reportedAmount());
            Evidence decided=jdbc.queryForObject("""
                UPDATE payroll_final_pay_bank_evidence SET status=?,reviewed_by=?,reviewed_at=now()
                WHERE tenant_id=? AND id=? AND status='SUBMITTED'
                RETURNING id,payment_authorization_id,bank_code,bank_reference,bank_value_date,
                    reported_amount,status,recorded_by,reviewed_by
                """,(rs,i)->map(rs),approve?"EVIDENCE_REVIEWED":"REJECTED",user.id(),user.tenantId(),id);
            audit(user,approve?"FINAL_PAY_BANK_EVIDENCE_REVIEWED":"FINAL_PAY_BANK_EVIDENCE_REJECTED",id);
            return decided;
        });
    }

    public List<Evidence> list(HrmUserPrincipal user,UUID paymentId) {
        return tenants.inTenant(user.tenantId(),()->jdbc.query("""
            SELECT id,payment_authorization_id,bank_code,bank_reference,bank_value_date,
                   reported_amount,status,recorded_by,reviewed_by
            FROM payroll_final_pay_bank_evidence WHERE tenant_id=? AND payment_authorization_id=?
            """,(rs,i)->map(rs),user.tenantId(),paymentId));
    }

    static void verifyAuthorizedPayment(String status,BigDecimal authorized,BigDecimal reported) {
        if(!"AUTHORIZED".equals(status) || authorized==null || reported==null ||
           authorized.compareTo(reported)!=0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Bank evidence must match an authorized positive payment instruction exactly");
    }
    static void requireIndependentEvidenceReview(String status,UUID recorder,UUID preparer,UUID authorizer,UUID reviewer) {
        if(!"SUBMITTED".equals(status))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Evidence already decided");
        if(reviewer==null || reviewer.equals(recorder) || reviewer.equals(preparer) || reviewer.equals(authorizer))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Evidence reviewer must be independent of recorder, payment preparer and payment authorizer");
    }

    private Payment payment(UUID tenant,UUID id) {
        List<Payment> rows=jdbc.query("""
            SELECT id,status,amount,prepared_by,authorized_by
            FROM payroll_final_pay_payment_authorization WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,i)->new Payment(rs.getObject("id",UUID.class),rs.getString("status"),
                rs.getBigDecimal("amount"),rs.getObject("prepared_by",UUID.class),
                rs.getObject("authorized_by",UUID.class)),tenant,id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment authorization not found");
        return rows.get(0);
    }
    private void audit(HrmUserPrincipal user,String action,UUID id) {
        jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,?,'payroll_final_pay_bank_evidence',?)",
            user.tenantId(),user.id(),action,id);
    }
    private static Evidence map(ResultSet rs) throws SQLException {
        return new Evidence(rs.getObject("id",UUID.class),
            rs.getObject("payment_authorization_id",UUID.class),rs.getString("bank_code"),
            rs.getString("bank_reference"),rs.getObject("bank_value_date",LocalDate.class),
            rs.getBigDecimal("reported_amount"),rs.getString("status"),
            rs.getObject("recorded_by",UUID.class),rs.getObject("reviewed_by",UUID.class),
            "MANUAL_EVIDENCE_ONLY: not verified against a bank API or an official bank statement");
    }
}
