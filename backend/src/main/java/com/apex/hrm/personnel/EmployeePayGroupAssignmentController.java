package com.apex.hrm.personnel;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Bridges the personnel employee record to the pay group used by payroll calculations. */
@RestController
@RequestMapping("/api/v1/personnel/employees/{employeeId}/pay-group-assignments")
public class EmployeePayGroupAssignmentController {
    private static final Set<String> PAYROLL_ROLES = Set.of("ROLE_TENANT_ADMIN", "ROLE_HR_ADMIN", "ROLE_PAYROLL_OFFICER");
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;

    public EmployeePayGroupAssignmentController(JdbcTemplate jdbc, TenantJdbcExecutor tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    public record Assignment(UUID id, UUID payGroupId, String payGroupCode, String payGroupName,
            String frequency, LocalDate effectiveFrom, LocalDate effectiveTo, String employmentType, String remarks) {}
    public record Input(@NotNull UUID payGroupId, @NotNull LocalDate effectiveFrom, @Size(max = 250) String remarks) {}
    private record EmployeeState(UUID companyId, LocalDate hireDate, String employmentType) {}
    private record Existing(UUID id, LocalDate effectiveFrom, LocalDate effectiveTo) {}

    @GetMapping
    public List<Assignment> list(@AuthenticationPrincipal HrmUserPrincipal user, @PathVariable UUID employeeId) {
        requirePayrollRole(user);
        return tenants.inTenant(user.tenantId(), () -> {
            ensureEmployee(user.tenantId(), employeeId);
            return jdbc.query("""
                    SELECT er.id,er.pay_group_id,pg.code AS pay_group_code,pg.name AS pay_group_name,pg.frequency,
                           er.effective_from,er.effective_to,er.employment_type,er.remarks
                    FROM employment_record er
                    JOIN pay_group pg ON pg.tenant_id=er.tenant_id AND pg.id=er.pay_group_id
                    WHERE er.tenant_id=? AND er.employee_id=?
                    ORDER BY er.effective_from DESC,er.id
                    """, (rs, row) -> map(rs), user.tenantId(), employeeId);
        });
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Assignment assign(@AuthenticationPrincipal HrmUserPrincipal user, @PathVariable UUID employeeId,
            @Valid @RequestBody Input input) {
        requirePayrollRole(user);
        return tenants.inTenant(user.tenantId(), () -> {
            List<EmployeeState> employees = jdbc.query("""
                    SELECT e.company_id,e.hire_date,p.employment_type
                    FROM employee e
                    JOIN personnel_employee p ON p.tenant_id=e.tenant_id AND p.id=e.id
                    WHERE e.tenant_id=? AND e.id=? FOR UPDATE OF e,p
                    """, (rs, row) -> new EmployeeState(rs.getObject("company_id", UUID.class),
                    rs.getObject("hire_date", LocalDate.class), rs.getString("employment_type")),
                    user.tenantId(), employeeId);
            if (employees.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found");
            EmployeeState employee = employees.get(0);
            if (input.effectiveFrom().isBefore(employee.hireDate()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pay group assignment predates hire");

            Integer groupCount = jdbc.queryForObject("""
                    SELECT count(*) FROM pay_group WHERE tenant_id=? AND id=? AND company_id=?
                    """, Integer.class, user.tenantId(), input.payGroupId(), employee.companyId());
            if (groupCount == null || groupCount == 0)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a pay group for the employee's company");

            List<Existing> latestRows = jdbc.query("""
                    SELECT id,effective_from,effective_to FROM employment_record
                    WHERE tenant_id=? AND employee_id=? ORDER BY effective_from DESC,id LIMIT 1 FOR UPDATE
                    """, (rs, row) -> new Existing(rs.getObject("id", UUID.class),
                    rs.getObject("effective_from", LocalDate.class), rs.getObject("effective_to", LocalDate.class)),
                    user.tenantId(), employeeId);
            if (!latestRows.isEmpty()) {
                Existing previous = latestRows.get(0);
                if (!input.effectiveFrom().isAfter(previous.effectiveFrom()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Pay group changes must follow the latest effective assignment");
                if (previous.effectiveTo() == null || !previous.effectiveTo().isBefore(input.effectiveFrom())) {
                    jdbc.update("""
                            UPDATE employment_record SET effective_to=?,updated_at=now()
                            WHERE tenant_id=? AND id=?
                            """, input.effectiveFrom().minusDays(1), user.tenantId(), previous.id());
                }
            }

            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO employment_record(id,tenant_id,employee_id,effective_from,employment_type,pay_group_id,remarks)
                    VALUES(?,?,?,?,?,?,?)
                    """, id, user.tenantId(), employeeId, input.effectiveFrom(), employee.employmentType(),
                    input.payGroupId(), input.remarks() == null ? "" : input.remarks().trim());
            jdbc.update("""
                    INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id,metadata)
                    VALUES(?,?,'EMPLOYEE_PAY_GROUP_ASSIGNED','employment_record',?,jsonb_build_object('pay_group_id',?::text,'effective_from',?::text))
                    """, user.tenantId(), user.id(), id, input.payGroupId(), input.effectiveFrom());
            return jdbc.queryForObject("""
                    SELECT er.id,er.pay_group_id,pg.code AS pay_group_code,pg.name AS pay_group_name,pg.frequency,
                           er.effective_from,er.effective_to,er.employment_type,er.remarks
                    FROM employment_record er JOIN pay_group pg ON pg.tenant_id=er.tenant_id AND pg.id=er.pay_group_id
                    WHERE er.tenant_id=? AND er.id=?
                    """, (rs, row) -> map(rs), user.tenantId(), id);
        });
    }

    private void ensureEmployee(UUID tenantId, UUID employeeId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM personnel_employee WHERE tenant_id=? AND id=?",
                Integer.class, tenantId, employeeId);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found");
    }

    private static void requirePayrollRole(HrmUserPrincipal user) {
        boolean authorized = user.getAuthorities().stream().anyMatch(authority -> PAYROLL_ROLES.contains(authority.getAuthority()));
        if (!authorized) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Payroll administration access is required");
    }

    private static Assignment map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Assignment(rs.getObject("id", UUID.class), rs.getObject("pay_group_id", UUID.class),
                rs.getString("pay_group_code"), rs.getString("pay_group_name"), rs.getString("frequency"),
                rs.getObject("effective_from", LocalDate.class), rs.getObject("effective_to", LocalDate.class),
                rs.getString("employment_type"), rs.getString("remarks"));
    }
}
