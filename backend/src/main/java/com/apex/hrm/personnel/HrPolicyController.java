package com.apex.hrm.personnel;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.security.WorkspaceAuditService;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/hr-policies")
public class HrPolicyController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    private final WorkspaceAuditService audit;
    public HrPolicyController(JdbcTemplate jdbc, TenantJdbcExecutor tenants, WorkspaceAuditService audit) {
        this.jdbc=jdbc; this.tenants=tenants; this.audit=audit;
    }
    public record Policy(UUID id,String title,String category,String summary,String body,String versionLabel,String status,
                         LocalDate effectiveOn,java.time.Instant publishedAt,UUID createdBy,java.time.Instant createdAt) {}
    public record Input(@NotBlank @Size(max=200) String title,@NotBlank @Size(max=80) String category,
                        @Size(max=500) String summary,@NotBlank @Size(max=50000) String body,
                        @NotBlank @Size(max=40) String versionLabel,LocalDate effectiveOn) {}
    private boolean manager(HrmUserPrincipal u) { return u.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_TENANT_ADMIN")||a.getAuthority().equals("ROLE_HR_ADMIN")); }
    @GetMapping public List<Policy> list(@AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(),()->jdbc.query("SELECT id,title,category,summary,body,version_label,status,effective_on,published_at,created_by,created_at FROM hr_policy WHERE tenant_id=? AND (? OR status='PUBLISHED') ORDER BY CASE status WHEN 'PUBLISHED' THEN 0 ELSE 1 END,effective_on DESC NULLS LAST,updated_at DESC LIMIT 500",
            (rs,n)->map(rs,n),user.tenantId(),manager(user)));
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Policy create(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody Input in) {
        requireManager(user);
        return tenants.inTenant(user.tenantId(),()->{ Policy p=jdbc.queryForObject("INSERT INTO hr_policy(tenant_id,title,category,summary,body,version_label,effective_on,created_by) VALUES(?,?,?,?,?,?,?,?) RETURNING id,title,category,summary,body,version_label,status,effective_on,published_at,created_by,created_at",(rs,n)->map(rs,n),user.tenantId(),in.title().trim(),in.category().trim(),clean(in.summary()),in.body().trim(),in.versionLabel().trim(),in.effectiveOn(),user.id()); audit.record(user.tenantId(),user.id(),"HR_POLICY_CREATED","hr_policy",p.id()); return p; });
    }
    @PutMapping("/{id}") public Policy update(@PathVariable UUID id,@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody Input in) {
        requireManager(user); return tenants.inTenant(user.tenantId(),()->{ Policy p=jdbc.query("UPDATE hr_policy SET title=?,category=?,summary=?,body=?,version_label=?,effective_on=?,updated_at=now() WHERE tenant_id=? AND id=? AND status='DRAFT' RETURNING id,title,category,summary,body,version_label,status,effective_on,published_at,created_by,created_at",(rs,n)->map(rs,n),in.title().trim(),in.category().trim(),clean(in.summary()),in.body().trim(),in.versionLabel().trim(),in.effectiveOn(),user.tenantId(),id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Draft policy not found or no longer editable")); audit.record(user.tenantId(),user.id(),"HR_POLICY_UPDATED","hr_policy",id); return p; });
    }
    @PostMapping("/{id}/publish") public Policy publish(@PathVariable UUID id,@AuthenticationPrincipal HrmUserPrincipal user) {
        requireManager(user); return tenants.inTenant(user.tenantId(),()->{ Policy p=jdbc.query("UPDATE hr_policy SET status='PUBLISHED',published_at=now(),updated_at=now() WHERE tenant_id=? AND id=? AND status='DRAFT' AND (effective_on IS NULL OR effective_on<=current_date) RETURNING id,title,category,summary,body,version_label,status,effective_on,published_at,created_by,created_at",(rs,n)->map(rs,n),user.tenantId(),id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Policy must be a draft with an effective date no later than today")); audit.record(user.tenantId(),user.id(),"HR_POLICY_PUBLISHED","hr_policy",id); return p; });
    }
    @PostMapping("/{id}/archive") public Policy archive(@PathVariable UUID id,@AuthenticationPrincipal HrmUserPrincipal user) {
        requireManager(user); return tenants.inTenant(user.tenantId(),()->{ Policy p=jdbc.query("UPDATE hr_policy SET status='ARCHIVED',updated_at=now() WHERE tenant_id=? AND id=? AND status='PUBLISHED' RETURNING id,title,category,summary,body,version_label,status,effective_on,published_at,created_by,created_at",(rs,n)->map(rs,n),user.tenantId(),id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Published policy not found")); audit.record(user.tenantId(),user.id(),"HR_POLICY_ARCHIVED","hr_policy",id); return p; });
    }
    private void requireManager(HrmUserPrincipal u) { if(u==null||!u.isEnabled()||!manager(u)||!u.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_MFA_VERIFIED"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"HR policy management requires verified HR administrator access"); }
    private static String clean(String s) { return s==null?"":s.trim(); }
    private static Policy map(java.sql.ResultSet rs, int n) throws java.sql.SQLException { return new Policy(rs.getObject("id",UUID.class),rs.getString("title"),rs.getString("category"),rs.getString("summary"),rs.getString("body"),rs.getString("version_label"),rs.getString("status"),rs.getObject("effective_on",LocalDate.class),rs.getTimestamp("published_at")==null?null:rs.getTimestamp("published_at").toInstant(),rs.getObject("created_by",UUID.class),rs.getTimestamp("created_at").toInstant()); }
}
