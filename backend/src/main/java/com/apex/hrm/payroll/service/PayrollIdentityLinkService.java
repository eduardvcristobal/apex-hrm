package com.apex.hrm.payroll.service;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Explicit, independently verified identity link between new Personnel and legacy payroll.
 * Name alone is never used to resolve an employee.
 */
@Service
public class PayrollIdentityLinkService {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public PayrollIdentityLinkService(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record Identity(UUID id,UUID personnelEmployeeId,UUID payrollEmployeeId,
        String status,UUID requestedBy,UUID decidedBy,String evidenceReference) {}
    public record Candidate(UUID companyId,String number,String firstName,String lastName,
        LocalDate hired, String status) {}
    public record Submission(UUID personnelEmployeeId,UUID payrollEmployeeId,String evidenceReference) {}

    public Identity request(HrmUserPrincipal user,Submission form) {
        if (form==null || form.personnelEmployeeId()==null || form.payrollEmployeeId()==null
            || form.evidenceReference()==null || form.evidenceReference().trim().length()<20
            || form.evidenceReference().length()>1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Two employee IDs and documented identity evidence are required");
        return tenants.inTenant(user.tenantId(),()->{
            verifyMatches(user.tenantId(),form.personnelEmployeeId(),form.payrollEmployeeId());
            List<Identity> rows=jdbc.query("""
                INSERT INTO payroll_personnel_identity_link
                 (tenant_id,personnel_employee_id,payroll_employee_id,evidence_reference,requested_by)
                VALUES (?,?,?,?,?)
                ON CONFLICT DO NOTHING
                RETURNING id,personnel_employee_id,payroll_employee_id,status,requested_by,decided_by,evidence_reference
                """,(rs,i)->map(rs),user.tenantId(),form.personnelEmployeeId(),
                 form.payrollEmployeeId(),form.evidenceReference().trim(),user.id());
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Identity already linked or pending verification");
            audit(user,"PAYROLL_IDENTITY_REQUESTED",rows.get(0).id());
            return rows.get(0);
        });
    }

    public Identity decide(HrmUserPrincipal user,UUID id,boolean approve) {
        return tenants.inTenant(user.tenantId(),()->{
            List<Identity> rows=jdbc.query("""
                SELECT id,personnel_employee_id,payroll_employee_id,status,requested_by,decided_by,evidence_reference
                FROM payroll_personnel_identity_link WHERE tenant_id=? AND id=? FOR UPDATE
                """,(rs,i)->map(rs),user.tenantId(),id);
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Identity link not found");
            Identity found=rows.get(0);
            requireIndependentReview(found.status(),found.requestedBy(),user.id());
            if(approve)verifyMatches(user.tenantId(),found.personnelEmployeeId(),found.payrollEmployeeId());
            Identity changed=jdbc.queryForObject("""
                UPDATE payroll_personnel_identity_link SET status=?,decided_by=?,decided_at=now()
                WHERE tenant_id=? AND id=? AND status='PENDING'
                RETURNING id,personnel_employee_id,payroll_employee_id,status,requested_by,decided_by,evidence_reference
                """,(rs,i)->map(rs),approve?"APPROVED":"REJECTED",user.id(),user.tenantId(),id);
            audit(user,approve?"PAYROLL_IDENTITY_APPROVED":"PAYROLL_IDENTITY_REJECTED",id);
            return changed;
        });
    }

    public List<Identity> list(HrmUserPrincipal user,UUID personnelId) {
        return tenants.inTenant(user.tenantId(),()->jdbc.query("""
            SELECT id,personnel_employee_id,payroll_employee_id,status,requested_by,decided_by,evidence_reference
            FROM payroll_personnel_identity_link WHERE tenant_id=? AND personnel_employee_id=?
            """,(rs,i)->map(rs),user.tenantId(),personnelId));
    }

    static void requireIndependentReview(String status,UUID requester,UUID reviewer) {
        if(!"PENDING".equals(status))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Identity link already decided");
        if(reviewer==null || reviewer.equals(requester))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"A different administrator must verify employee identity");
    }

    static void confirmCandidateMatch(Candidate personnel,Candidate payroll) {
        if(personnel==null || payroll==null
                || !personnel.companyId().equals(payroll.companyId())
                || !personnel.number().equals(payroll.number())
                || !canon(personnel.firstName()).equals(canon(payroll.firstName()))
                || !canon(personnel.lastName()).equals(canon(payroll.lastName()))
                || !personnel.hired().equals(payroll.hired()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Company, employee number, legal names and hire date must match before linking");
    }

    private static String canon(String value) {
        return value==null?"":value.trim().replaceAll("\\s+"," ").toUpperCase(Locale.ROOT);
    }

    private void verifyMatches(UUID tenant,UUID personnel,UUID payroll) {
        List<Candidate> p=jdbc.query("""
            SELECT company_id,employee_number,first_name,last_name,hire_date,employment_status
            FROM personnel_employee WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,i)->candidate(rs,"employee_number","employment_status"),tenant,personnel);
        List<Candidate> l=jdbc.query("""
            SELECT company_id,employee_no,first_name,last_name,hire_date,status
            FROM employee WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,i)->candidate(rs,"employee_no","status"),tenant,payroll);
        if(p.size()!=1 || l.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee identity record not found");
        confirmCandidateMatch(p.get(0),l.get(0));
    }

    private static Candidate candidate(ResultSet rs,String no,String status) throws SQLException {
        return new Candidate(rs.getObject("company_id",UUID.class),rs.getString(no),
           rs.getString("first_name"),rs.getString("last_name"),
           rs.getObject("hire_date",LocalDate.class),rs.getString(status));
    }
    private void audit(HrmUserPrincipal user,String action,UUID id) {
        jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,?,'payroll_personnel_identity_link',?)",
            user.tenantId(),user.id(),action,id);
    }
    private static Identity map(ResultSet rs) throws SQLException {
        return new Identity(rs.getObject("id",UUID.class),
            rs.getObject("personnel_employee_id",UUID.class),
            rs.getObject("payroll_employee_id",UUID.class),rs.getString("status"),
            rs.getObject("requested_by",UUID.class),rs.getObject("decided_by",UUID.class),
            rs.getString("evidence_reference"));
    }
}
