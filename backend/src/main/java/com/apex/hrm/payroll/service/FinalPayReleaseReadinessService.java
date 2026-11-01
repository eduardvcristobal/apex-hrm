package com.apex.hrm.payroll.service;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Release readiness is intentionally informational. No current endpoint can mark
 * final-pay payable or bank-confirmed merely because an issuer signed a digest.
 */
@Service
public class FinalPayReleaseReadinessService {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    private final FinalPaySourceSnapshotService sources;
    private final FinalPayReconciliationService reconciliation;
    public FinalPayReleaseReadinessService(JdbcTemplate jdbc,TenantJdbcExecutor tenants,
            FinalPaySourceSnapshotService sources,FinalPayReconciliationService reconciliation){
        this.jdbc=jdbc;this.tenants=tenants;this.sources=sources;this.reconciliation=reconciliation;
    }
    public record Readiness(UUID settlementId,String status,String currentSnapshotStatus,
        String sourceReviewStatus,String periodAttestationStatus,String issuerDigestStatus,
        String paymentReviewStatus,boolean sourceCurrent,boolean sourceComplete,
        boolean issuerSignatureForDigestVerified,boolean actualDocumentBytesVerified,
        boolean bankConfirmed,boolean independentBankAccountOwnershipVerified,
        boolean statutoryFinalPayCertified,boolean payoutCertified,
        boolean disbursementEnabled,List<String> blockingReasons) {}
    private record Evidence(UUID snapshotId,String sourceReviewStatus,String attestationStatus,
                            String digestStatus) {}

    public Readiness preflight(HrmUserPrincipal user,UUID settlementId){
        return tenants.inTenant(user.tenantId(),()->{
            Integer n=jdbc.queryForObject("""
                SELECT count(*) FROM payroll_final_pay_settlement
                WHERE tenant_id=? AND id=?
                """,Integer.class,user.tenantId(),settlementId);
            if(n==null || n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Final-pay settlement not found");
            List<Evidence> rows=jdbc.query("""
                SELECT s.id,COALESCE(r.status,'NOT_REVIEWED'),
                       COALESCE(a.status,'NOT_ATTESTED'),
                       COALESCE(v.verification_status,'NOT_VERIFIED')
                FROM payroll_final_pay_source_snapshot s
                LEFT JOIN payroll_final_pay_source_review r
                    ON r.tenant_id=s.tenant_id AND r.snapshot_id=s.id
                LEFT JOIN payroll_final_pay_period_attestation a
                    ON a.tenant_id=s.tenant_id AND a.snapshot_id=s.id
                LEFT JOIN payroll_final_pay_document_verification v
                    ON v.tenant_id=s.tenant_id AND v.attestation_id=a.id
                WHERE s.tenant_id=? AND s.settlement_id=?
                ORDER BY s.captured_at DESC,s.id DESC LIMIT 1
                """,(rs,i)->new Evidence(rs.getObject(1,UUID.class),
                    rs.getString(2),rs.getString(3),rs.getString(4)),
                user.tenantId(),settlementId);
            String current="NO_SOURCE_SNAPSHOT";
            boolean currentHash=false,complete=false;
            String source="NOT_REVIEWED",period="NOT_ATTESTED",digest="NOT_VERIFIED";
            if(!rows.isEmpty()){
                Evidence e=rows.get(0);source=e.sourceReviewStatus();
                period=e.attestationStatus();digest=e.digestStatus();
                FinalPaySourceSnapshotService.Freshness fresh=sources.verifyCurrent(user,e.snapshotId());
                current=fresh.reviewStatus();
                currentHash=fresh.current()&&fresh.latest();
                complete=currentHash&&"READY_FOR_INDEPENDENT_REVIEW".equals(current);
            }
            FinalPayReconciliationService.Preflight payment=
                reconciliation.preflight(user,settlementId);
            List<String> blockers=blockingReasons(currentHash,complete,source,period,digest,
                payment.controlStatus());
            return new Readiness(settlementId,"PRODUCTION_NO_GO",current,source,period,digest,
                payment.controlStatus(),currentHash,complete,
                "ISSUER_SIGNATURE_VALID_FOR_DIGEST".equals(digest),
                false,false,false,false,false,false,List.copyOf(blockers));
        });
    }
    static List<String> blockingReasons(boolean sourceCurrent,boolean sourceComplete,
       String sourceReview,String attestation,String digest,String payment){
        List<String> list=new ArrayList<>();
        if(!sourceCurrent)list.add("Current immutable payroll/Personnel source snapshot required");
        if(!sourceComplete)list.add("Complete payroll cutoff, compensation and DTR sources required");
        if(!"SOURCE_REVIEWED".equals(sourceReview))
            list.add("Independent financial-source review outstanding");
        if(!"INTERNALLY_REVIEWED".equals(attestation))
            list.add("Independent period evidence review outstanding");
        if(!"ISSUER_SIGNATURE_VALID_FOR_DIGEST".equals(digest))
            list.add("Trusted issuer's Ed25519 signature over attested document digest missing");
        list.add("Actual external document bytes and issuer provenance not independently fetched and hashed");
        list.add("Bank account ownership and provider-controlled payment confirmation missing");
        list.add("Statutory final-pay, tax and government remittance certification outstanding");
        if(!"MANUAL_EVIDENCE_REVIEWED".equals(payment))
            list.add("Internal manual payment/evidence review incomplete: "+payment);
        list.add("Bank statement settlement, exception/reversal and independent release authorization not implemented");
        return list;
    }
}
