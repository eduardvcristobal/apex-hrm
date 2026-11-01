package com.apex.hrm.payroll.service;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal finance closure-attestation workflow only. A supplied document digest is a
 * user assertion: the app does not obtain bank statements or validate issuer signatures.
 * No disbursement, statutory certification, or PAID status is produced here.
 */
@Service
public class FinalPayPeriodAttestationService {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    private final FinalPaySourceSnapshotService snapshots;

    public FinalPayPeriodAttestationService(JdbcTemplate jdbc,TenantJdbcExecutor tenants,
                                            FinalPaySourceSnapshotService snapshots){
        this.jdbc=jdbc;this.tenants=tenants;this.snapshots=snapshots;
    }
    public record Request(UUID sourceReviewId,String issuerName,String documentReference,
                          String declaredDocumentSha256) {}
    public record Attestation(UUID id,UUID settlementId,UUID snapshotId,UUID sourceReviewId,
        String sourceSha256,String issuerName,String documentReference,
        String declaredDocumentSha256,String provenanceStatus,String status,
        UUID requestedBy,UUID reviewedBy,String acceptanceMeaning,
        boolean issuerIndependentlyVerified,boolean bankConfirmed,
        boolean payoutCertified,boolean disbursementEnabled) {}
    private record Source(UUID reviewId,UUID settlementId,UUID snapshotId,
                          String sourceSha256,String status,UUID requestedBy,
                          UUID reviewedBy,UUID capturedBy) {}

    static String requireDigest(String hash) {
        if(hash==null || !hash.matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A lowercase 64-character SHA-256 document fingerprint is required");
        return hash;
    }
    static void requireIndependent(String status,UUID requester,UUID capturedBy,
                                   UUID sourceReviewer,UUID reviewer) {
        if(!"PENDING".equals(status))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Attestation already decided");
        if(reviewer==null || reviewer.equals(requester) || reviewer.equals(capturedBy)
            || (sourceReviewer!=null && reviewer.equals(sourceReviewer)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Attestation reviewer must be independent of requester, snapshot capturer and source reviewer");
    }
    public Attestation submit(HrmUserPrincipal user,Request req) {
        if(req==null || req.sourceReviewId()==null || req.issuerName()==null
            || req.issuerName().trim().length()<3 || req.issuerName().length()>200
            || req.documentReference()==null || req.documentReference().trim().length()<20
            || req.documentReference().length()>1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Existing source review, declared issuer and documented reference are required");
        String digest=requireDigest(req.declaredDocumentSha256());
        return tenants.inTenant(user.tenantId(),()->{
            Source src=source(user.tenantId(),req.sourceReviewId(),false);
            FinalPaySourceSnapshotService.Freshness fresh=snapshots.verifyCurrent(user,src.snapshotId());
            if(!fresh.current() || !fresh.latest() || !fresh.capturedSha256().equals(src.sourceSha256()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot attest stale/superseded source evidence");
            List<Attestation> created=jdbc.query("""
                INSERT INTO payroll_final_pay_period_attestation
                   (tenant_id,settlement_id,snapshot_id,source_review_id,source_sha256,
                    issuer_name,document_reference,declared_document_sha256,requested_by)
                VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id,snapshot_id) DO NOTHING
                RETURNING id,settlement_id,snapshot_id,source_review_id,source_sha256,
                 issuer_name,document_reference,declared_document_sha256,provenance_status,
                 status,requested_by,reviewed_by
                """,(rs,i)->map(rs),user.tenantId(),src.settlementId(),src.snapshotId(),
                src.reviewId(),src.sourceSha256(),req.issuerName().trim(),
                req.documentReference().trim(),digest,user.id());
            if(created.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Snapshot already has a period-closure attestation");
            audit(user,"FINAL_PAY_PERIOD_ATTESTATION_SUBMITTED",created.get(0).id());
            return created.get(0);
        });
    }
    public Attestation decide(HrmUserPrincipal user,UUID attestationId,boolean accept) {
        return tenants.inTenant(user.tenantId(),()->{
            List<Attestation> rows=jdbc.query("""
                SELECT id,settlement_id,snapshot_id,source_review_id,source_sha256,
                  issuer_name,document_reference,declared_document_sha256,provenance_status,
                  status,requested_by,reviewed_by
                FROM payroll_final_pay_period_attestation
                WHERE tenant_id=? AND id=? FOR UPDATE
                """,(rs,i)->map(rs),user.tenantId(),attestationId);
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Period attestation not found");
            Attestation a=rows.get(0);
            Source src=source(user.tenantId(),a.sourceReviewId(),false);
            requireIndependent(a.status(),a.requestedBy(),src.capturedBy(),
                src.reviewedBy(),user.id());
            if(accept) {
                FinalPaySourceSnapshotService.Freshness fresh=snapshots.verifyCurrent(user,a.snapshotId());
                requireClosureEligible(src.status(),fresh.reviewStatus(),fresh.current(),fresh.latest(),
                    a.sourceSha256().equals(src.sourceSha256())
                      && a.sourceSha256().equals(fresh.capturedSha256()));
            }
            Attestation result=jdbc.queryForObject("""
                UPDATE payroll_final_pay_period_attestation
                SET status=?,reviewed_by=?,reviewed_at=now()
                WHERE tenant_id=? AND id=? AND status='PENDING'
                RETURNING id,settlement_id,snapshot_id,source_review_id,source_sha256,
                  issuer_name,document_reference,declared_document_sha256,provenance_status,
                  status,requested_by,reviewed_by
                """,(rs,i)->map(rs),accept?"INTERNALLY_REVIEWED":"REJECTED",
                user.id(),user.tenantId(),attestationId);
            audit(user,accept?"FINAL_PAY_PERIOD_EVIDENCE_REVIEWED":"FINAL_PAY_PERIOD_EVIDENCE_REJECTED",
                attestationId);
            return result;
        });
    }
    static void requireClosureEligible(String reviewStatus,String freshnessStatus,
                                      boolean current,boolean latest,boolean shaMatch) {
        if(!"SOURCE_REVIEWED".equals(reviewStatus)
            || !"READY_FOR_INDEPENDENT_REVIEW".equals(freshnessStatus)
            || !current || !latest || !shaMatch)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Period closure cannot be reviewed positively without approved complete current source evidence");
    }
    public List<Attestation> list(HrmUserPrincipal user,UUID settlementId){
        return tenants.inTenant(user.tenantId(),()->{
            Integer n=jdbc.queryForObject("""
                SELECT count(*) FROM payroll_final_pay_settlement WHERE tenant_id=? AND id=?
                """,Integer.class,user.tenantId(),settlementId);
            if(n==null || n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Final pay settlement not found");
            return jdbc.query("""
                SELECT id,settlement_id,snapshot_id,source_review_id,source_sha256,
                 issuer_name,document_reference,declared_document_sha256,provenance_status,
                 status,requested_by,reviewed_by
                FROM payroll_final_pay_period_attestation
                WHERE tenant_id=? AND settlement_id=?
                ORDER BY created_at DESC,id DESC LIMIT 100
                """,(rs,i)->map(rs),user.tenantId(),settlementId);
        });
    }
    private Source source(UUID tenant,UUID reviewId,boolean lock){
        List<Source> rows=jdbc.query("""
            SELECT r.id,r.settlement_id,r.snapshot_id,r.requested_sha256,r.status,
              r.requested_by,r.decided_by,s.captured_by
            FROM payroll_final_pay_source_review r
            JOIN payroll_final_pay_source_snapshot s
              ON s.tenant_id=r.tenant_id AND s.id=r.snapshot_id
            WHERE r.tenant_id=? AND r.id=?
            """,(rs,i)->new Source(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getString(4),rs.getString(5),
                rs.getObject(6,UUID.class),rs.getObject(7,UUID.class),
                rs.getObject(8,UUID.class)),tenant,reviewId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Financial source review not found");
        return rows.get(0);
    }
    private void audit(HrmUserPrincipal user,String action,UUID id){
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES(?,?,?,'payroll_final_pay_period_attestation',?)
            """,user.tenantId(),user.id(),action,id);
    }
    private static Attestation map(ResultSet rs)throws SQLException{
        return new Attestation(rs.getObject("id",UUID.class),
            rs.getObject("settlement_id",UUID.class),rs.getObject("snapshot_id",UUID.class),
            rs.getObject("source_review_id",UUID.class),rs.getString("source_sha256"),
            rs.getString("issuer_name"),rs.getString("document_reference"),
            rs.getString("declared_document_sha256"),rs.getString("provenance_status"),
            rs.getString("status"),rs.getObject("requested_by",UUID.class),
            rs.getObject("reviewed_by",UUID.class),
            "INTERNAL EVIDENCE REVIEW ONLY; NOT A BANK ACKNOWLEDGMENT OR PAYROLL CLOSE",false,false,false,false);
    }
}
