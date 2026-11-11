package com.apex.hrm.personnel;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Compensation is an append-only administrative ledger; no public employee salary endpoint. */
@RestController("saasCompensationController")
@RequestMapping("/api/v1/personnel/employees/{employeeId}/compensation")
public class CompensationController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public CompensationController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record Compensation(UUID id,LocalDate effectiveDate,BigDecimal amount,
        String currencyCode,String payBasis,String reason) {}
    public record NewCompensation(
        @NotNull LocalDate effectiveDate,
        @NotNull @DecimalMin("0.01") @Digits(integer=13,fraction=2) BigDecimal amount,
        @NotBlank @Pattern(regexp="MONTHLY|DAILY|HOURLY") String payBasis,
        @NotBlank @Size(max=250) String reason
    ) {}
    @GetMapping
    public List<Compensation> list(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId) {
        return tenants.inTenant(user.tenantId(),()->{
            requireEmployee(user.tenantId(),employeeId);
            return jdbc.query("""
                SELECT id,effective_date,amount,currency_code,pay_basis,reason
                FROM personnel_compensation_history WHERE tenant_id=? AND employee_id=?
                ORDER BY effective_date DESC LIMIT 100
                """,(rs,i)->map(rs),user.tenantId(),employeeId);
        });
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Compensation create(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
            @Valid @RequestBody NewCompensation input) {
        return tenants.inTenant(user.tenantId(),()->{
            List<LocalDate> hires=jdbc.query("""
                SELECT hire_date FROM personnel_employee WHERE tenant_id=? AND id=? FOR UPDATE
                """,(rs,i)->rs.getObject(1,LocalDate.class),user.tenantId(),employeeId);
            if(hires.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
            if(input.effectiveDate().isBefore(hires.get(0)))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Compensation predates hire");
            LocalDate latest=jdbc.queryForObject("""
                SELECT max(effective_date) FROM personnel_compensation_history WHERE tenant_id=? AND employee_id=?
                """,LocalDate.class,user.tenantId(),employeeId);
            UUID timekeepingId=jdbc.query("SELECT employee_id FROM personnel_timekeeping_employee WHERE tenant_id=? AND personnel_employee_id=?",
                (rs,i)->rs.getObject(1,UUID.class),user.tenantId(),employeeId).stream().findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Employee is not linked to payroll"));
            LocalDate payrollLatest=jdbc.queryForObject("SELECT max(effective_from) FROM compensation WHERE tenant_id=? AND employee_id=?",
                LocalDate.class,user.tenantId(),timekeepingId);
            if(latest!=null && !input.effectiveDate().isAfter(latest))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Compensation history is append only");
            if(payrollLatest!=null && !input.effectiveDate().isAfter(payrollLatest))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Effective date must follow existing payroll compensation");
            Compensation result=jdbc.queryForObject("""
                INSERT INTO personnel_compensation_history
                  (tenant_id,employee_id,effective_date,amount,pay_basis,reason,recorded_by)
                VALUES(?,?,?,?,?,?,?)
                RETURNING id,effective_date,amount,currency_code,pay_basis,reason
                """,(rs,i)->map(rs),user.tenantId(),employeeId,input.effectiveDate(),
                input.amount(),input.payBasis(),input.reason().trim(),user.id());
            jdbc.update("UPDATE compensation SET effective_to=?,updated_at=now() WHERE tenant_id=? AND employee_id=? AND effective_to IS NULL AND effective_from<?",
                input.effectiveDate().minusDays(1),user.tenantId(),timekeepingId,input.effectiveDate());
            jdbc.update("INSERT INTO compensation(tenant_id,employee_id,effective_from,pay_basis,base_rate,reason) VALUES(?,?,?,?,?,?)",
                user.tenantId(),timekeepingId,input.effectiveDate(),input.payBasis(),input.amount(),input.reason().trim());
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'COMPENSATION_RECORDED','personnel_compensation_history',?)
                """,user.tenantId(),user.id(),result.id());
            return result;
        });
    }
    private void requireEmployee(UUID tenantId,UUID employeeId) {
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM personnel_employee WHERE tenant_id=? AND id=?
            """,Integer.class,tenantId,employeeId);
        if(count==null||count==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
    }
    private static Compensation map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Compensation(rs.getObject("id",UUID.class),rs.getObject("effective_date",LocalDate.class),
            rs.getBigDecimal("amount"),rs.getString("currency_code"),
            rs.getString("pay_basis"),rs.getString("reason"));
    }
}
