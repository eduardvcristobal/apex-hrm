package com.apex.hrm.payroll.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.payroll.domain.WageOrder;
import com.apex.hrm.payroll.repo.WageOrderRepository;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reference/wage-orders")
public class WageOrderController extends CrudController<WageOrder> {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;

    public WageOrderController(WageOrderRepository repo, ObjectMapper mapper, JdbcTemplate jdbc, TenantJdbcExecutor tenants) {
        super(repo, mapper);
        this.jdbc=jdbc;
        this.tenants=tenants;
    }

    public record ImpactInput(@NotBlank String regionCode,@NotBlank String sector,@NotNull LocalDate effectiveDate,
                              @NotNull @DecimalMin("0.01") BigDecimal dailyRate) {}
    public record ImpactEmployee(UUID employeeId,String employeeNo,String firstName,String lastName,UUID positionId,
                                 BigDecimal currentDailyRate,BigDecimal proposedDailyRate,BigDecimal dailyIncrease,
                                 long samePositionHeadcount,BigDecimal positionLowestDailyRate,BigDecimal positionHighestDailyRate) {}
    public record ImpactPreview(String regionCode,String sector,LocalDate effectiveDate,BigDecimal proposedMinimum,
                                int affectedEmployeeCount,BigDecimal dailyBaseIncreaseTotal,List<ImpactEmployee> affected,
                                String reviewNote) {}

    /** Read-only impact view; final wage-distortion treatment remains an explicit employer review. */
    @PostMapping("/impact-preview")
    public ImpactPreview preview(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody ImpactInput input) {
        String region=input.regionCode().trim().toUpperCase();
        String sector=input.sector().trim().toUpperCase();
        if(!List.of("NON_AGRI","AGRI","DOMESTIC_WORKER").contains(sector))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Unsupported wage sector");
        return tenants.inTenant(user.tenantId(),()->{
            List<ImpactEmployee> affected=jdbc.query("""
                WITH active_job AS (
                  SELECT DISTINCT ON (employee_id) employee_id,position_id,work_location_id,pay_group_id
                  FROM employment_record WHERE effective_from<=? AND (effective_to IS NULL OR effective_to>=?)
                  ORDER BY employee_id,effective_from DESC
                ), active_pay AS (
                  SELECT DISTINCT ON (employee_id) employee_id,pay_basis,base_rate
                  FROM compensation WHERE effective_from<=? AND (effective_to IS NULL OR effective_to>=?)
                  ORDER BY employee_id,effective_from DESC
                ), rates AS (
                  SELECT e.id,e.employee_no,e.first_name,e.last_name,j.position_id,l.region_code,l.wage_sector,
                    CASE c.pay_basis WHEN 'DAILY' THEN c.base_rate WHEN 'HOURLY' THEN c.base_rate*coalesce(g.hours_per_day,8)
                      WHEN 'MONTHLY' THEN c.base_rate*12/nullif(coalesce(g.days_factor,261),0) END AS daily_rate
                  FROM employee e JOIN active_job j ON j.employee_id=e.id JOIN work_location l ON l.id=j.work_location_id
                  JOIN active_pay c ON c.employee_id=e.id LEFT JOIN pay_group g ON g.id=j.pay_group_id
                  WHERE e.status IN ('ACTIVE','ON_LEAVE') AND l.region_code=? AND l.wage_sector=?
                ), peers AS (
                  SELECT *,count(*) OVER(PARTITION BY coalesce(position_id,id)) AS peer_count,
                    min(daily_rate) OVER(PARTITION BY coalesce(position_id,id)) AS peer_min,
                    max(daily_rate) OVER(PARTITION BY coalesce(position_id,id)) AS peer_max
                  FROM rates WHERE daily_rate IS NOT NULL
                )
                SELECT id,employee_no,first_name,last_name,position_id,daily_rate,peer_count,peer_min,peer_max
                FROM peers WHERE daily_rate < ? ORDER BY position_id NULLS LAST,daily_rate,employee_no
                """,(rs,row)->{
                    BigDecimal current=rs.getBigDecimal("daily_rate");
                    return new ImpactEmployee(rs.getObject("id",UUID.class),rs.getString("employee_no"),
                        rs.getString("first_name"),rs.getString("last_name"),rs.getObject("position_id",UUID.class),
                        current,input.dailyRate(),input.dailyRate().subtract(current).setScale(2,RoundingMode.HALF_UP),
                        rs.getLong("peer_count"),rs.getBigDecimal("peer_min"),rs.getBigDecimal("peer_max"));
                },input.effectiveDate(),input.effectiveDate(),input.effectiveDate(),input.effectiveDate(),region,sector,input.dailyRate());
            BigDecimal total=affected.stream().map(ImpactEmployee::dailyIncrease).reduce(BigDecimal.ZERO,BigDecimal::add);
            return new ImpactPreview(region,sector,input.effectiveDate(),input.dailyRate(),affected.size(),total,
                affected,"Review affected employees and same-position pay ranges for wage-distortion risk before applying any salary movement. This preview does not calculate a legally required adjustment.");
        });
    }
}
