package com.apex.hrm.timekeeping.web;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Event-based leave awards require configured benefit rules, evidence, and independent eligibility approval. */
@RestController
@RequestMapping("/api/leave-benefit-claims")
public class LeaveBenefitClaimController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;

    public LeaveBenefitClaimController(JdbcTemplate jdbc, TenantJdbcExecutor tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    public record ClaimInput(@NotNull UUID employeeId, @NotNull UUID leaveTypeId, @NotNull LocalDate eventDate,
                             @NotBlank @Size(max=120) String eventReference, @NotNull @DecimalMin("0.25") BigDecimal requestedDays,
                             @NotBlank @Size(max=255) String evidenceReference, @Size(max=1000) String reason) {}
    public record Decision(@NotBlank String decision, boolean eligibilityVerified) {}
    public record Reimbursement(@NotNull @DecimalMin("0") BigDecimal salaryDifferential,
                                @NotNull @DecimalMin("0") BigDecimal reimbursementDue,
                                @NotBlank String status, @Size(max=255) String reference) {}

    @GetMapping
    public List<Map<String,Object>> list(@AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(), () -> jdbc.queryForList("""
            SELECT c.id,c.employee_id,e.employee_no,e.first_name,e.last_name,c.leave_type_id,t.code AS leave_code,
              t.name AS leave_name,c.profile_id,c.event_date,c.event_reference,c.requested_days,c.evidence_reference,
              c.reason,c.status,c.eligibility_verified,c.requested_by,c.decided_by,c.decided_at,
              c.employer_salary_differential,c.agency_reimbursement_due,c.reimbursement_status,c.reimbursement_reference
            FROM leave_benefit_claim c JOIN employee e ON e.tenant_id=c.tenant_id AND e.id=c.employee_id
              JOIN leave_type t ON t.tenant_id=c.tenant_id AND t.id=c.leave_type_id
            WHERE c.tenant_id=? ORDER BY c.event_date DESC,c.created_at DESC LIMIT 500
            """, user.tenantId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String,Object> create(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody ClaimInput in) {
        return tenants.inTenant(user.tenantId(), () -> {
            List<UUID> profiles = jdbc.query("""
                SELECT profile_id FROM employee_leave_benefit_assignment WHERE tenant_id=? AND employee_id=?
                  AND effective_from<=? AND (effective_to IS NULL OR effective_to>=?)
                ORDER BY effective_from DESC LIMIT 1
                """, (rs,row)->rs.getObject(1,UUID.class), user.tenantId(),in.employeeId(),in.eventDate(),in.eventDate());
            if (profiles.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Employee has no active leave benefit profile on the event date");
            List<BigDecimal> limits = jdbc.query("""
                SELECT annual_entitlement FROM leave_benefit_entitlement WHERE tenant_id=? AND profile_id=? AND leave_type_id=?
                  AND grant_basis='EVENT' AND effective_from<=? AND (effective_to IS NULL OR effective_to>=?)
                ORDER BY effective_from DESC LIMIT 1
                """,(rs,row)->rs.getBigDecimal(1),user.tenantId(),profiles.get(0),in.leaveTypeId(),in.eventDate(),in.eventDate());
            if (limits.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"This leave type is not configured as an event-based entitlement for the employee's profile");
            if (in.requestedDays().compareTo(limits.get(0))>0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Requested days exceed the configured maximum for this event");
            try {
                Map<String,Object> row=jdbc.queryForMap("""
                    INSERT INTO leave_benefit_claim(tenant_id,employee_id,leave_type_id,profile_id,event_date,event_reference,
                      requested_days,evidence_reference,reason,requested_by)
                    VALUES(?,?,?,?,?,?,?,?,?,?) RETURNING id,employee_id,leave_type_id,profile_id,event_date,event_reference,
                      requested_days,evidence_reference,reason,status,eligibility_verified,requested_by,decided_by,decided_at,
                      employer_salary_differential,agency_reimbursement_due,reimbursement_status,reimbursement_reference
                    """,user.tenantId(),in.employeeId(),in.leaveTypeId(),profiles.get(0),in.eventDate(),in.eventReference().trim(),
                    in.requestedDays(),in.evidenceReference().trim(),clean(in.reason()),user.id());
                audit(user,"LEAVE_BENEFIT_CLAIM_REQUESTED",(UUID)row.get("id"));
                return row;
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,"A claim already exists for this employee, leave type, event date, and reference");
            }
        });
    }

    @PostMapping("/{claimId}/decision")
    @Transactional
    public Map<String,Object> decide(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID claimId,
                                     @Valid @RequestBody Decision input) {
        String decision=input.decision().trim().toUpperCase();
        if (!decision.equals("APPROVED")&&!decision.equals("REJECTED")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Decision must be APPROVED or REJECTED");
        if (decision.equals("APPROVED")&&!input.eligibilityVerified()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Verify statutory eligibility before approval");
        return tenants.inTenant(user.tenantId(), () -> {
            List<Map<String,Object>> claims=jdbc.queryForList("SELECT * FROM leave_benefit_claim WHERE tenant_id=? AND id=? AND status='PENDING' FOR UPDATE",user.tenantId(),claimId);
            if(claims.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Pending leave benefit claim not found");
            Map<String,Object> claim=claims.get(0);
            if(user.id().equals(claim.get("requested_by"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Independent approval is required");
            if(decision.equals("APPROVED")) {
                jdbc.update("""
                    INSERT INTO leave_ledger(tenant_id,employee_id,leave_type_id,entry_date,entry_type,days,reference_type,reference_id,remarks)
                    VALUES(?,?,?,?,'ACCRUAL',?,'STATUTORY_LEAVE_CLAIM',?,?)
                    """,user.tenantId(),claim.get("employee_id"),claim.get("leave_type_id"),claim.get("event_date"),
                    claim.get("requested_days"),claimId,"Approved event-based leave: "+claim.get("event_reference"));
            }
            jdbc.update("UPDATE leave_benefit_claim SET status=?,eligibility_verified=?,decided_by=?,decided_at=now(),updated_at=now() WHERE tenant_id=? AND id=?",
                    decision,decision.equals("APPROVED")&&input.eligibilityVerified(),user.id(),user.tenantId(),claimId);
            audit(user,"LEAVE_BENEFIT_CLAIM_"+decision,claimId);
            return jdbc.queryForMap("SELECT * FROM leave_benefit_claim WHERE tenant_id=? AND id=?",user.tenantId(),claimId);
        });
    }

    @PutMapping("/{claimId}/reimbursement")
    public Map<String,Object> reimbursement(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID claimId,
                                             @Valid @RequestBody Reimbursement input) {
        String status=input.status().trim().toUpperCase();
        if(!List.of("NOT_TRACKED","PENDING","SUBMITTED","REIMBURSED","REJECTED").contains(status))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid reimbursement status");
        return tenants.inTenant(user.tenantId(),()->{
            int updated=jdbc.update("""
                UPDATE leave_benefit_claim SET employer_salary_differential=?,agency_reimbursement_due=?,
                  reimbursement_status=?,reimbursement_reference=?,updated_at=now()
                WHERE tenant_id=? AND id=? AND status='APPROVED'
                """,input.salaryDifferential(),input.reimbursementDue(),status,clean(input.reference()),user.tenantId(),claimId);
            if(updated==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only approved leave claims can track salary differential or agency reimbursement");
            audit(user,"LEAVE_BENEFIT_CLAIM_REIMBURSEMENT_UPDATED",claimId);
            return jdbc.queryForMap("SELECT * FROM leave_benefit_claim WHERE tenant_id=? AND id=?",user.tenantId(),claimId);
        });
    }

    private void audit(HrmUserPrincipal user,String action,UUID id) {
        jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,?,?,?)",user.tenantId(),user.id(),action,"leave_benefit_claim",id);
    }
    private static String clean(String value) { return value==null?null:value.trim(); }
}
