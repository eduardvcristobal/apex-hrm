package com.apex.hrm.payroll.service;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validates a detached Ed25519 signature against a pinned deployment-owned public key.
 * This authenticates a claim about a document DIGEST, not the actual document bytes.
 * No user-submitted public keys, real bank confirmation or payout decisions.
 */
@Service
public class FinalPayDocumentVerificationService {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    private final FinalPaySourceSnapshotService snapshots;
    private final String trustedKeyId;
    private final String trustedPublicKey;

    public FinalPayDocumentVerificationService(JdbcTemplate jdbc,TenantJdbcExecutor tenants,
        FinalPaySourceSnapshotService snapshots,
        @Value("${hrm.financial.documents.issuer-key-id:}") String keyId,
        @Value("${hrm.financial.documents.issuer-public-key-base64:}") String key){
        this.jdbc=jdbc;this.tenants=tenants;this.snapshots=snapshots;this.trustedKeyId=keyId;this.trustedPublicKey=key;
    }
    public record Request(UUID attestationId,String issuerKeyId,String signatureBase64) {}
    public record Verification(UUID id,UUID attestationId,String issuerKeyId,
        String signedClaimSha256,String verificationStatus,UUID verifiedBy,
        boolean documentBytesVerified,boolean bankConfirmed,boolean payoutCertified,
        boolean disbursementEnabled) {}
    private record Attestation(UUID id,String documentHash,String status,
        UUID requestedBy,UUID reviewedBy,UUID sourceReviewer,UUID snapshotCapturer,
        UUID snapshotId,String sourceSha256,String sourceReviewStatus) {}

    static String claim(UUID tenant,UUID attestationId,String sha256,String keyId){
        return "APEX-HRM-DOCUMENT-DIGEST-V1\\n"+tenant+"\\n"+attestationId
            +"\\n"+sha256+"\\n"+keyId+"\\n";
    }
    /** Does not accept keys from a request. Decoded X509 SubjectPublicKeyInfo, Ed25519 only. */
    static boolean verifySignature(String publicKeyBase64,String message,String signatureBase64){
        try {
            byte[] encoded=Base64.getDecoder().decode(publicKeyBase64);
            byte[] signature=Base64.getDecoder().decode(signatureBase64);
            PublicKey pub=KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(encoded));
            Signature verifier=Signature.getInstance("Ed25519");
            verifier.initVerify(pub);
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signature);
        } catch(Exception ex) { return false; }
    }
    static void requireIndependentVerification(UUID actor,UUID requester,UUID reviewer,
                                               UUID sourceReviewer,UUID capturer){
        if(actor==null || actor.equals(requester) || actor.equals(reviewer)
            || actor.equals(sourceReviewer) || actor.equals(capturer))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Verification requires an officer independent from prior source and document attestors");
    }
    public Verification verify(HrmUserPrincipal user,Request input){
        if(input==null || input.attestationId()==null
            || input.issuerKeyId()==null || input.issuerKeyId().length()>100
            || input.signatureBase64()==null || input.signatureBase64().length()>512
            || input.signatureBase64().length()<20)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Attestation, trusted issuer key identifier and detached signature are required");
        return tenants.inTenant(user.tenantId(),()->{
            if(trustedKeyId.isBlank() || trustedPublicKey.isBlank())
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No independently provisioned trusted issuer verification key is configured");
            if(!trustedKeyId.equals(input.issuerKeyId()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Issuer key ID is not pinned by deployment configuration");
            List<Attestation> rows=jdbc.query("""
                SELECT a.id,a.declared_document_sha256,a.status,a.requested_by,
                    a.reviewed_by,s.decided_by AS source_reviewer,
                    p.captured_by AS snapshot_capturer,
                    p.id AS source_snapshot_id,a.source_sha256,s.status AS source_review_status
                FROM payroll_final_pay_period_attestation a
                JOIN payroll_final_pay_source_review s
                    ON s.tenant_id=a.tenant_id AND s.id=a.source_review_id
                JOIN payroll_final_pay_source_snapshot p
                    ON p.tenant_id=a.tenant_id AND p.id=a.snapshot_id
                WHERE a.tenant_id=? AND a.id=? FOR UPDATE OF a
                """,(rs,i)->new Attestation(rs.getObject("id",UUID.class),
                    rs.getString("declared_document_sha256"),rs.getString("status"),
                    rs.getObject("requested_by",UUID.class),rs.getObject("reviewed_by",UUID.class),
                    rs.getObject("source_reviewer",UUID.class),
                    rs.getObject("snapshot_capturer",UUID.class),
                    rs.getObject("source_snapshot_id",UUID.class),
                    rs.getString("source_sha256"),rs.getString("source_review_status")),
                    user.tenantId(),input.attestationId());
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Period attestation not found");
            Attestation a=rows.get(0);
            if(!"INTERNALLY_REVIEWED".equals(a.status()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Document signature proof requires completed independent period evidence review");
            requireIndependentVerification(user.id(),a.requestedBy(),a.reviewedBy(),
                a.sourceReviewer(),a.snapshotCapturer());
            FinalPaySourceSnapshotService.Freshness fresh=snapshots.verifyCurrent(user,a.snapshotId());
            if(!"SOURCE_REVIEWED".equals(a.sourceReviewStatus())
                || !fresh.current() || !fresh.latest()
                || !"READY_FOR_INDEPENDENT_REVIEW".equals(fresh.reviewStatus())
                || !a.sourceSha256().equals(fresh.capturedSha256()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Issuer digest cannot be verified against incomplete, stale or unreviewed payroll sources");
            String message=claim(user.tenantId(),a.id(),a.documentHash(),trustedKeyId);
            if(!verifySignature(trustedPublicKey,message,input.signatureBase64()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Detached signature is not valid for the pinned issuer and document digest");
            List<Verification> stored=jdbc.query("""
                INSERT INTO payroll_final_pay_document_verification
                  (tenant_id,attestation_id,issuer_key_id,signed_claim_sha256,
                   detached_signature_base64,verification_status,verified_by)
                VALUES(?,?,?,?,?,'ISSUER_SIGNATURE_VALID_FOR_DIGEST',?)
                ON CONFLICT(tenant_id,attestation_id) DO NOTHING
                RETURNING id,attestation_id,issuer_key_id,signed_claim_sha256,
                          verification_status,verified_by
                """,(rs,i)->map(rs),user.tenantId(),a.id(),trustedKeyId,a.documentHash(),
                input.signatureBase64(),user.id());
            if(stored.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Document claim has already been independently checked");
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'FINAL_PAY_ISSUER_DIGEST_SIGNATURE_VERIFIED',
                    'payroll_final_pay_document_verification',?)
                """,user.tenantId(),user.id(),stored.get(0).id());
            return stored.get(0);
        });
    }
    public List<Verification> list(HrmUserPrincipal user,UUID settlementId){
        return tenants.inTenant(user.tenantId(),()->{
            Integer n=jdbc.queryForObject("""
                SELECT count(*) FROM payroll_final_pay_settlement WHERE tenant_id=? AND id=?
                """,Integer.class,user.tenantId(),settlementId);
            if(n==null || n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Final-pay settlement not found");
            return jdbc.query("""
                SELECT v.id,v.attestation_id,v.issuer_key_id,v.signed_claim_sha256,
                       v.verification_status,v.verified_by
                FROM payroll_final_pay_document_verification v
                JOIN payroll_final_pay_period_attestation a
                    ON a.tenant_id=v.tenant_id AND a.id=v.attestation_id
                WHERE v.tenant_id=? AND a.settlement_id=?
                ORDER BY v.verified_at DESC,v.id DESC LIMIT 100
                """,(rs,i)->map(rs),user.tenantId(),settlementId);
        });
    }
    private static Verification map(ResultSet rs)throws SQLException {
        return new Verification(rs.getObject("id",UUID.class),
            rs.getObject("attestation_id",UUID.class),rs.getString("issuer_key_id"),
            rs.getString("signed_claim_sha256"),rs.getString("verification_status"),
            rs.getObject("verified_by",UUID.class),false,false,false,false);
    }
}
