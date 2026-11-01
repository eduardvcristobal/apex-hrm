package com.apex.hrm.payroll.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.TenantContext;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.payroll.domain.BankFile;
import com.apex.hrm.payroll.domain.PayrollEmployee;
import com.apex.hrm.payroll.domain.PayrollLine;
import com.apex.hrm.payroll.domain.PayrollRun;
import com.apex.hrm.payroll.repo.PayrollEmployeeRepository;
import com.apex.hrm.payroll.repo.PayrollLineRepository;
import com.apex.hrm.payroll.repo.PayrollRunRepository;
import com.apex.hrm.payroll.repo.PayslipRepository;
import com.apex.hrm.payroll.service.BankFileService;
import com.apex.hrm.payroll.service.PayrollService;
import com.apex.hrm.timekeeping.domain.DtrDay;
import com.apex.hrm.timekeeping.repo.DtrDayRepository;
import com.apex.hrm.timekeeping.service.AttendanceService;
import com.apex.hrm.timekeeping.service.DtrService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M3.2.6 — production Spring services, real tenant-scoped Hibernate repositories and PostgreSQL.
 * All people and accounts are synthetic. This is NOT external bank settlement certification.
 * Only enabled with a dedicated, disposable HRM_TEST_DB_URL.
 */
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=${HRM_TEST_DB_URL:jdbc:postgresql://localhost:5432/apex_hrm_test}",
        "spring.datasource.username=${HRM_TEST_DB_USER:hrm}",
        "spring.datasource.password=${HRM_TEST_DB_PASSWORD:hrm}"
})
@EnabledIfEnvironmentVariable(named = "HRM_TEST_DB_URL", matches = ".+")
class PostgreSqlPayrollLifecycleE2ETest {
    private static final LocalDate FROM = LocalDate.of(2026, 10, 5);
    private static final LocalDate TO = LocalDate.of(2026, 10, 6);

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @Autowired AttendanceService attendance;
    @Autowired DtrService dtr;
    @Autowired PayrollService payroll;
    @Autowired BankFileService bankFiles;
    @Autowired PayrollRunRepository runs;
    @Autowired PayrollEmployeeRepository payrollEmployees;
    @Autowired PayrollLineRepository payrollLines;
    @Autowired DtrDayRepository dtrDays;
    @Autowired PayslipRepository payslips;
    @Autowired ObjectMapper json;

    @AfterEach
    void cleanupContext() {
        TenantContext.clear();
    }

    @Test
    void syntheticEmployeeCompletesEntirePersistedPayCycleAndTenantIsolation() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID company = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        UUID payGroup = UUID.randomUUID();
        UUID period = UUID.randomUUID();
        UUID schedule = UUID.randomUUID();
        UUID bankTemplate = UUID.randomUUID();

        jdbc.update("INSERT INTO tenant(id,code,name,slug,display_name) VALUES (?,?,?,?,?)",
                tenant, "CERT-" + tenant.toString().substring(0, 12), "Synthetic certification tenant",
                "cert-" + tenant, "Synthetic certification tenant");
        jdbc.update("INSERT INTO company(id,tenant_id,legal_name,company_code) VALUES (?,?,?,?)",
                company, tenant, "M3.2 Test Company", "CERT-CO");
        jdbc.update("INSERT INTO pay_group(id,tenant_id,company_id,code,name) VALUES (?,?,?,?,?)",
                payGroup, tenant, company, "CERT-PAY", "Test Semimonthly");
        jdbc.update("INSERT INTO employee(id,tenant_id,company_id,employee_no,first_name,last_name,hire_date) " +
                "VALUES (?,?,?,?,?,?,?)", employee, tenant, company, "CERT-0001", "Maria", "Sample",
                java.sql.Date.valueOf(LocalDate.of(2025, 1, 1)));
        jdbc.update("INSERT INTO employment_record(tenant_id,employee_id,effective_from,employment_type,pay_group_id) " +
                "VALUES (?,?,?,?,?)", tenant, employee, java.sql.Date.valueOf(LocalDate.of(2025, 1, 1)),
                "REGULAR", payGroup);
        jdbc.update("INSERT INTO compensation(tenant_id,employee_id,effective_from,pay_basis,base_rate) " +
                "VALUES (?,?,?,?,?)", tenant, employee, java.sql.Date.valueOf(LocalDate.of(2025, 1, 1)),
                "MONTHLY", new BigDecimal("26100"));
        jdbc.update("INSERT INTO work_schedule(id,tenant_id,code,name,start_time,end_time,break_minutes,grace_minutes,rest_days) " +
                "VALUES (?,?,?,?,?,?,?,?,?)", schedule, tenant, "CERT-DAY", "Certification shift",
                java.sql.Time.valueOf("09:00:00"), java.sql.Time.valueOf("18:00:00"), 60, 5, "SAT,SUN");
        jdbc.update("INSERT INTO employee_schedule(tenant_id,employee_id,schedule_id,effective_from) VALUES (?,?,?,?)",
                tenant, employee, schedule, java.sql.Date.valueOf(FROM));
        jdbc.update("INSERT INTO pay_period(id,tenant_id,pay_group_id,period_of_month,start_date,end_date,pay_date) " +
                "VALUES (?,?,?,?,?,?,?)", period, tenant, payGroup, 1, java.sql.Date.valueOf(FROM),
                java.sql.Date.valueOf(TO), java.sql.Date.valueOf(LocalDate.of(2026, 10, 10)));
        jdbc.update("INSERT INTO employee_bank_account(tenant_id,employee_id,bank_code,account_no,account_name) " +
                "VALUES (?,?,?,?,?)", tenant, employee, "CERTBANK", "0000001234", "Maria Sample");
        jdbc.update("INSERT INTO bank_template(id,tenant_id,bank_code,name,column_spec) VALUES (?,?,?,?,?)",
                bankTemplate, tenant, "CERTBANK", "Synthetic test bank",
                "EMPLOYEE_NO,ACCOUNT_NO,AMOUNT");

        TenantContext.set(tenant);
        attendance.recordManual(employee, "IN", Instant.parse("2026-10-05T01:30:00Z"), "M3.2 fixture");
        attendance.recordManual(employee, "OUT", Instant.parse("2026-10-05T11:00:00Z"), "M3.2 fixture");
        var authority = List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"),
                new SimpleGrantedAuthority("ROLE_MFA_VERIFIED"));
        var principal = new HrmUserPrincipal(UUID.randomUUID(), tenant, "cert@example.invalid",
                "unused", true, authority);
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authority);
        mockMvc.perform(post("/api/dtr/process")
                .with(authentication(authentication)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new DtrRequest(FROM, TO, employee))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.processedDays").value(2));
        // TenantFilter clears request-local context after dispatch; restore it for direct service assertions.
        TenantContext.set(tenant);
        DtrDay worked = dtrDays.findByEmployeeIdAndWorkDate(employee, FROM).orElseThrow();
        DtrDay absent = dtrDays.findByEmployeeIdAndWorkDate(employee, TO).orElseThrow();
        assertEquals(450, worked.getRegularMinutes());
        assertEquals(30, worked.getLateMinutes());
        assertEquals(60, worked.getOtDetectedMinutes());
        assertEquals(0, worked.getOtApprovedMinutes());
        assertTrue(absent.getAbsent());

        PayrollRun created = payroll.createRun(period, "REGULAR");
        PayrollRun computed = payroll.compute(created.getId());
        assertEquals("COMPUTED", computed.getStatus());
        assertEquals(1, computed.getEmployeeCount());
        List<PayrollEmployee> included = payrollEmployees.findByRunId(created.getId());
        assertEquals(1, included.size());
        PayrollEmployee pe = included.get(0);
        List<PayrollLine> detail = payrollLines.findByRunId(created.getId());
        assertMoney("13050.00", lineAmount(detail, "BASIC"));
        assertMoney("75.00", lineAmount(detail, "LATE_UT"));
        assertMoney("1200.00", lineAmount(detail, "ABSENCE"));
        assertMoney("0", lineAmount(detail, "OT")); // no approved OT, even if detected
        assertMoney("11775.00", pe.getGross());
        assertEquals(0, pe.getGross().subtract(pe.getTotalDeductions())
                .compareTo(pe.getNetPay()), "employee reconciliation");
        assertMoney(pe.getNetPay(), computed.getTotalNet());

        payroll.review(created.getId());
        payroll.approve(created.getId(), UUID.randomUUID());
        payroll.lock(created.getId());
        assertEquals("LOCKED", dtrDays.findByEmployeeIdAndWorkDate(employee, FROM).orElseThrow().getStatus());
        assertEquals("LOCKED", dtrDays.findByEmployeeIdAndWorkDate(employee, TO).orElseThrow().getStatus());
        assertEquals(0, dtr.process(FROM, TO, employee), "locked DTR must not change");
        assertThrows(BusinessException.class, () -> attendance.recordManual(employee, "OUT",
                Instant.parse("2026-10-05T12:00:00Z"), "after payroll lock"));

        var issued = payslips.findByPayrollEmployeeIdOrderByVersionNoDesc(pe.getId());
        assertEquals(1, issued.size(), "one snapshot per locked employee payroll");
        JsonNode snapshot = json.readTree(issued.get(0).getSnapshot());
        assertEquals("CERT-0001", snapshot.path("employeeNo").asText());
        assertMoney(pe.getNetPay(), new BigDecimal(snapshot.path("netPay").asText()));

        BankFileService.Result export = bankFiles.generate(created.getId(), bankTemplate);
        assertEquals(List.of(), export.skippedEmployees());
        BankFile bank = export.file();
        assertEquals(1, bank.getRecordCount());
        assertMoney(pe.getNetPay(), bank.getTotalAmount());
        assertTrue(bank.getContent().contains("CERT-0001,0000001234,"));
        mockMvc.perform(post("/api/payroll/runs/" + created.getId() + "/pay")
                .with(authentication(authentication)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("PAID"));
        TenantContext.set(tenant);
        assertEquals("PAID", runs.findById(created.getId()).orElseThrow().getStatus());

        UUID otherTenant = UUID.randomUUID();
        boolean[] isolated = CompletableFuture.supplyAsync(() -> {
            TenantContext.set(otherTenant);
            try {
                return new boolean[] {
                    runs.findById(created.getId()).isEmpty(),
                    payrollEmployees.findByRunId(created.getId()).isEmpty(),
                    dtrDays.findByEmployeeIdAndWorkDate(employee, FROM).isEmpty()
                };
            } finally {
                TenantContext.clear();
            }
        }).join();
        assertTrue(isolated[0], "other tenant cannot read payroll run");
        assertTrue(isolated[1], "other tenant cannot read payroll employee");
        assertTrue(isolated[2], "other tenant cannot read DTR");
        TenantContext.set(tenant);
        assertEquals("PAID", runs.findById(created.getId()).orElseThrow().getStatus());
    }

    private record DtrRequest(LocalDate from, LocalDate to, UUID employeeId) {}

    private static BigDecimal lineAmount(List<PayrollLine> lines, String code) {
        return lines.stream().filter(l -> code.equals(l.getItemCode())).map(PayrollLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void assertMoney(String expected, BigDecimal amount) {
        assertEquals(0, new BigDecimal(expected).compareTo(amount), "actual " + amount);
    }

    private static void assertMoney(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual), "expected " + expected + " actual " + actual);
    }
}
