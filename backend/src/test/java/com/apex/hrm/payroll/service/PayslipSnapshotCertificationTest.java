package com.apex.hrm.payroll.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.apex.hrm.payroll.domain.*;
import com.apex.hrm.payroll.repo.PayslipRepository;
import com.apex.hrm.personnel.domain.Employee;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M3.2: the payslip must reproduce the locked payroll lines and totals, not live compensation. */
class PayslipSnapshotCertificationTest {
    @Test
    void lockedPayrollProducesReconciledImmutableSnapshot() throws Exception {
        UUID employeeId = UUID.randomUUID();
        UUID payrollEmployeeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        EmployeeRepository employees = mock(EmployeeRepository.class);
        PayslipRepository repository = mock(PayslipRepository.class);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Employee employee = new Employee();
        employee.setId(employeeId);
        employee.setEmployeeNo("EMP-1001");
        employee.setFirstName("Maria");
        employee.setLastName("Santos");
        when(employees.findById(employeeId)).thenReturn(Optional.of(employee));
        when(repository.findByPayrollEmployeeIdOrderByVersionNoDesc(payrollEmployeeId))
                .thenReturn(List.of());
        when(repository.save(any(Payslip.class))).thenAnswer(call -> call.getArgument(0));

        PayPeriod period = new PayPeriod();
        period.setStartDate(LocalDate.of(2026, 10, 1));
        period.setEndDate(LocalDate.of(2026, 10, 15));
        period.setPayDate(LocalDate.of(2026, 10, 20));
        PayrollRun run = new PayrollRun();
        run.setId(runId);
        run.setRunType("REGULAR");
        PayrollEmployee pe = new PayrollEmployee();
        pe.setId(payrollEmployeeId);
        pe.setEmployeeId(employeeId);
        pe.setGross(new BigDecimal("15000.00"));
        pe.setTotalDeductions(new BigDecimal("2500.00"));
        pe.setNetPay(new BigDecimal("12500.00"));
        PayrollLine basic = new PayrollLine();
        basic.setCategory("EARNING");
        basic.setItemCode("BASIC");
        basic.setDescription("Basic pay");
        basic.setAmount(new BigDecimal("15000.00"));
        PayrollLine tax = new PayrollLine();
        tax.setCategory("DEDUCTION");
        tax.setItemCode("WTAX");
        tax.setDescription("Withholding tax");
        tax.setAmount(new BigDecimal("2500.00"));

        Payslip result = new PayslipService(repository, employees, json)
                .issue(run, period, pe, List.of(basic, tax));
        JsonNode snapshot = json.readTree(result.getSnapshot());
        assertEquals(1, result.getVersionNo());
        assertEquals(runId, result.getRunId());
        assertEquals(employeeId, result.getEmployeeId());
        assertEquals("EMP-1001", snapshot.path("employeeNo").asText());
        assertEquals("2026-10-15", snapshot.path("periodEnd").asText());
        assertEquals("BASIC", snapshot.path("earnings").get(0).path("code").asText());
        assertEquals("WTAX", snapshot.path("deductions").get(0).path("code").asText());
        assertEquals(0, new BigDecimal(snapshot.path("gross").asText()).compareTo(new BigDecimal("15000")));
        assertEquals(0, new BigDecimal(snapshot.path("totalDeductions").asText()).compareTo(new BigDecimal("2500")));
        assertEquals(0, new BigDecimal(snapshot.path("netPay").asText()).compareTo(new BigDecimal("12500")));
        assertEquals(0, new BigDecimal(snapshot.path("gross").asText())
                .subtract(new BigDecimal(snapshot.path("totalDeductions").asText()))
                .compareTo(new BigDecimal(snapshot.path("netPay").asText())));
        employee.setLastName("Changed Later");
        assertEquals("Santos, Maria", json.readTree(result.getSnapshot()).path("employeeName").asText());
        assertNotNull(result.getIssuedAt());
    }
}
