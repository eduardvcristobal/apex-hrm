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

/** Independent, audit-backed source-evidence review. Never certifies final pay or triggers banking. */
@Service
public class FinalPaySourceReviewService {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    private final FinalPaySourceSnapshotService snapshots;

    public FinalPaySourceReviewService(JdbcTemplate jdbc,TenantJdbcExecutor tenants,
                                      FinalPaySourceSnapshotService snapshots){
        this.jdbc=jdbc;this.tenants=tenants;this.snapshots=snapshots;
    }

    public record Review(UUID id,UUID settlementId,UUID snapshotId,String sourceSha256,
        String evidenceReference,String status,UUID requestedBy,UUID decidedBy,
        String sourceReviewMeaning,boolean payoutCertified,boolean disbursementEnabled) {}

    public record Request(UUID snapshotId,String evidenceReference) {}

    public Review request(HrmUserPrincipal user,Request input){
        if(input==null || input.snapshotId()==null || input.evidenceReference()==null
            || input.evidenceReference().trim().length()<20 || input.evidenceReference().length()>1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Source snapshot and supporting evidence reference of at least 20 characters are required");
        return tenants.inTenant(user.tenantId(),()->{
            FinalPaySourceSnapshotService.Freshness fresh=
                snapshots.verifyCurrent(user,input.snapshotId());
            // A discrepancy can be requested for independent rejection, but cannot be approved.
            if(!fresh.current() || !fresh.latest())
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Source evidence is stale or superseded; capture an updated snapshot");
            List<Review> created=jdbc.query("""
                INSERT INTO payroll_final_pay_source_review
                  (tenant_id,settlement_id,snapshot_id,requested_sha256,
                   evidence_reference,requested_by)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT (tenant_id,snapshot_id) DO NOTHING
                RETURNING id,settlement_id,snapshot_id,requested_sha256,
                          evidence_reference,status,requested_by,decided_by
                """,(rs,i)->map(rs),user.tenantId(),fresh.settlementId(),fresh.snapshotId(),
                fresh.capturedSha256(),input.evidenceReference().trim(),user.id());
            if(created.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This source snapshot has already been submitted for independent review");
            audit(user,"FINAL_PAY_SOURCE_REVIEW_REQUESTED",created.get(0).id());
            return created.get(0);
        });
    }

    public Review decide(HrmUserPrincipal user,UUID reviewId,boolean accept){
        return tenants.inTenant(user.tenantId(),()->{
            List<Review> found=jdbc.query("""
                SELECT id,settlement_id,snapshot_id,requested_sha256,
                       evidence_reference,status,requested_by,decided_by
                FROM payroll_final_pay_source_review
                WHERE tenant_id=? AND id=? FOR UPDATE
                """,(rs,i)->map(rs),user.tenantId(),reviewId);
            if(found.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Source review not found");
            Review pending=found.get(0);
            requireIndependentDecision(pending.status(),pending.requestedBy(),user.id());
            UUID capturedBy=jdbc.queryForObject("""
                SELECT captured_by FROM payroll_final_pay_source_snapshot
                WHERE tenant_id=? AND id=?
                """,UUID.class,user.tenantId(),pending.snapshotId());
            requireIndependentCapture(capturedBy,user.id());
            FinalPaySourceSnapshotService.Freshness fresh=
                snapshots.verifyCurrent(user,pending.snapshotId());
            if(accept && (!fresh.current() || !fresh.latest()
                || !fresh.capturedSha256().equals(pending.sourceSha256())
                || !"READY_FOR_INDEPENDENT_REVIEW".equals(fresh.reviewStatus())))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Financial source snapshot is stale, superseded or incomplete");
            Review updated=jdbc.queryForObject("""
                UPDATE payroll_final_pay_source_review
                SET status=?,decided_by=?,decided_at=now()
                WHERE tenant_id=? AND id=? AND status='PENDING'
                RETURNING id,settlement_id,snapshot_id,requested_sha256,
                          evidence_reference,status,requested_by,decided_by
                """,(rs,i)->map(rs),accept?"SOURCE_REVIEWED":"REJECTED",user.id(),
                user.tenantId(),reviewId);
            audit(user,accept?"FINAL_PAY_SOURCE_REVIEW_ACCEPTED":"FINAL_PAY_SOURCE_REVIEW_REJECTED",
                updated.id());
            return updated;
        });
    }

    public List<Review> list(HrmUserPrincipal user,UUID settlementId){
        return tenants.inTenant(user.tenantId(),()->{
            Integer exists=jdbc.queryForObject("""
                SELECT count(*) FROM payroll_final_pay_settlement WHERE tenant_id=? AND id=?
                """,Integer.class,user.tenantId(),settlementId);
            if(exists==null || exists==0)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Final pay settlement not found");
            return jdbc.query("""
                SELECT id,settlement_id,snapshot_id,requested_sha256,
                       evidence_reference,status,requested_by,decided_by
                FROM payroll_final_pay_source_review
                WHERE tenant_id=? AND settlement_id=?
                ORDER BY created_at DESC,id DESC LIMIT 100
                """,(rs,i)->map(rs),user.tenantId(),settlementId);
        });
    }

    static void requireIndependentCapture(UUID capturedBy,UUID reviewer){
        if(capturedBy==null || reviewer==null || capturedBy.equals(reviewer))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Source reviewer cannot be the officer who captured the source snapshot");
    }
    static void requireIndependentDecision(String status,UUID requester,UUID reviewer){
        if(!"PENDING".equals(status))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Source review already decided");
        if(reviewer==null || reviewer.equals(requester))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "A different financial reviewer must decide the source review");
    }
    private void audit(HrmUserPrincipal user,String action,UUID id){
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES (?,?,?,'payroll_final_pay_source_review',?)
            """,user.tenantId(),user.id(),action,id);
    }
    private static Review map(ResultSet rs)throws SQLException{
        return new Review(rs.getObject("id",UUID.class),rs.getObject("settlement_id",UUID.class),
            rs.getObject("snapshot_id",UUID.class),rs.getString("requested_sha256"),
            rs.getString("evidence_reference"),rs.getString("status"),
            rs.getObject("requested_by",UUID.class),rs.getObject("decided_by",UUID.class),
            "SOURCE COMPLETENESS ONLY: NOT LEGAL FINAL-PAY OR BANK CONFIRMATION",
            false,false);
    }
}
