package com.apex.hrm.timekeeping.service;

import com.apex.hrm.platform.repo.TenantRepository;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Posts annual profile grants and carry-over expiry once per tenant/year. */
@Component
public class LeaveBenefitAccrualScheduler {
    private static final ZoneId HRM_ZONE = ZoneId.of("Asia/Manila");
    private final TenantRepository tenants;
    private final TenantJdbcExecutor tenantJdbc;
    private final JdbcTemplate jdbc;

    public LeaveBenefitAccrualScheduler(TenantRepository tenants,TenantJdbcExecutor tenantJdbc,JdbcTemplate jdbc) {
        this.tenants=tenants;this.tenantJdbc=tenantJdbc;this.jdbc=jdbc;
    }

    @Scheduled(cron="0 15 0 * * *",zone="Asia/Manila")
    public void accrueCurrentYear() {
        LocalDate yearStart=LocalDate.now(HRM_ZONE).withDayOfYear(1);
        for(var tenant:tenants.findAll()) {
            if(!"ACTIVE".equals(tenant.getStatus())) continue;
            UUID tenantId=tenant.getId();
            tenantJdbc.inTenant(tenantId,()->{
                jdbc.update("INSERT INTO leave_ledger(tenant_id,employee_id,leave_type_id,entry_date,entry_type,days,reference_type,reference_id,remarks) SELECT ?,a.employee_id,e.leave_type_id,?,'EXPIRY',e.carry_over_max-coalesce((SELECT sum(l.days) FROM leave_ledger l WHERE l.tenant_id=a.tenant_id AND l.employee_id=a.employee_id AND l.leave_type_id=e.leave_type_id AND l.entry_date<?),0),'BENEFIT_PROFILE_EXPIRY',a.id,p.name||' carry-over cap applied '||extract(year from ?::date)::int FROM employee_leave_benefit_assignment a JOIN leave_benefit_profile p ON p.tenant_id=a.tenant_id AND p.id=a.profile_id JOIN leave_benefit_entitlement e ON e.tenant_id=a.tenant_id AND e.profile_id=a.profile_id WHERE a.tenant_id=? AND a.effective_from<? AND (a.effective_to IS NULL OR a.effective_to>=?) AND e.effective_from<=? AND (e.effective_to IS NULL OR e.effective_to>=?) AND e.carry_over_max IS NOT NULL AND coalesce((SELECT sum(l.days) FROM leave_ledger l WHERE l.tenant_id=a.tenant_id AND l.employee_id=a.employee_id AND l.leave_type_id=e.leave_type_id AND l.entry_date<?),0)>e.carry_over_max ON CONFLICT (tenant_id,reference_id,leave_type_id,entry_date) WHERE reference_type='BENEFIT_PROFILE_EXPIRY' DO NOTHING",tenantId,yearStart,yearStart,yearStart,tenantId,yearStart,yearStart,yearStart,yearStart,yearStart);
                return jdbc.update("INSERT INTO leave_ledger(tenant_id,employee_id,leave_type_id,entry_date,entry_type,days,reference_type,reference_id,remarks) SELECT ?,a.employee_id,e.leave_type_id,?,'ACCRUAL',e.annual_entitlement,'BENEFIT_PROFILE_ANNUAL',a.id,p.name||' annual leave entitlement '||extract(year from ?::date)::int FROM employee_leave_benefit_assignment a JOIN leave_benefit_profile p ON p.tenant_id=a.tenant_id AND p.id=a.profile_id JOIN leave_benefit_entitlement e ON e.tenant_id=a.tenant_id AND e.profile_id=a.profile_id WHERE a.tenant_id=? AND a.effective_from<? AND (a.effective_to IS NULL OR a.effective_to>=?) AND e.effective_from<=? AND (e.effective_to IS NULL OR e.effective_to>=?) AND e.annual_entitlement>0 ON CONFLICT (tenant_id,reference_id,leave_type_id,entry_date) WHERE reference_type='BENEFIT_PROFILE_ANNUAL' DO NOTHING",tenantId,yearStart,yearStart,tenantId,yearStart,yearStart,yearStart,yearStart);
            });
        }
    }
}
