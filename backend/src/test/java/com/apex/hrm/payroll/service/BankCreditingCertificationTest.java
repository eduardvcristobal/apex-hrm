package com.apex.hrm.payroll.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.payroll.domain.*;
import com.apex.hrm.payroll.repo.*;
import com.apex.hrm.personnel.domain.Employee;
import com.apex.hrm.personnel.domain.EmployeeBankAccount;
import com.apex.hrm.personnel.repo.EmployeeBankAccountRepository;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.platform.service.AuditService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** M3.2: confirm bank crediting amounts reconcile exactly to positive-net payroll employees. */
class BankCreditingCertificationTest {
    private final PayrollRunRepository runs = mock(PayrollRunRepository.class);
    private final PayPeriodRepository periods = mock(PayPeriodRepository.class);
    private final PayrollEmployeeRepository payrollEmployees = mock(PayrollEmployeeRepository.class);
    private final BankTemplateRepository templates = mock(BankTemplateRepository.class);
    private final BankFileRepository files = mock(BankFileRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final EmployeeBankAccountRepository accounts = mock(EmployeeBankAccountRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final BankCreditImportRepository imports = mock(BankCreditImportRepository.class);
    private final BankCreditResultRepository creditResults = mock(BankCreditResultRepository.class);
    private final UUID runId = UUID.randomUUID();
    private final UUID periodId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();
    private BankFileService service;

    @BeforeEach
    void setup() {
        service = new BankFileService(runs, periods, payrollEmployees, templates, files, employees, accounts, audit,
                imports, creditResults);
        PayrollRun run = new PayrollRun();
        run.setId(runId);
        run.setPayPeriodId(periodId);
        run.setStatus("LOCKED");
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        PayPeriod period = new PayPeriod();
        period.setPayDate(LocalDate.of(2026, 10, 20));
        when(periods.findById(periodId)).thenReturn(Optional.of(period));
        BankTemplate template = new BankTemplate();
        template.setBankCode("TESTBANK");
        template.setColumnSpec("EMPLOYEE_NO,ACCOUNT_NO,AMOUNT");
        when(templates.findById(templateId)).thenReturn(Optional.of(template));
        when(files.save(any(BankFile.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void bankFileReconcilesAndReportsUnbankedEmployees() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        PayrollEmployee first = payrollEmployee(firstId, "12500.00");
        PayrollEmployee unbanked = payrollEmployee(secondId, "7500.00");
        when(payrollEmployees.findByRunId(runId)).thenReturn(List.of(first, unbanked));
        when(employees.findById(firstId)).thenReturn(Optional.of(employee(firstId, "EMP-1001")));
        when(employees.findById(secondId)).thenReturn(Optional.of(employee(secondId, "EMP-1002")));
        EmployeeBankAccount account = new EmployeeBankAccount();
        account.setBankCode("TESTBANK");
        account.setAccountNo("123456789");
        when(accounts.findByEmployeeId(firstId)).thenReturn(List.of(account));
        when(accounts.findByEmployeeId(secondId)).thenReturn(List.of());

        BankFileService.Result result = service.generate(runId, templateId);
        assertEquals(1, result.file().getRecordCount());
        assertEquals(0, result.file().getTotalAmount().compareTo(new BigDecimal("12500.00")));
        assertEquals(List.of("EMP-1002"), result.skippedEmployees());
        assertTrue(result.file().getContent().contains("EMP-1001,123456789,12500.00"));
        assertFalse(result.file().getContent().contains("EMP-1002"));
    }


    @Test
    void recoveryExportIncludesOnlyRejectedEmployeeAndLinksOriginalBatch() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        PayrollEmployee first = payrollEmployee(firstId, "12500.00");
        PayrollEmployee second = payrollEmployee(secondId, "7500.00");
        when(payrollEmployees.findByRunId(runId)).thenReturn(List.of(first, second));
        when(employees.findById(firstId)).thenReturn(Optional.of(employee(firstId, "EMP-1001")));
        when(employees.findById(secondId)).thenReturn(Optional.of(employee(secondId, "EMP-1002")));
        EmployeeBankAccount one = new EmployeeBankAccount();
        one.setBankCode("TESTBANK");
        one.setAccountNo("111111111");
        EmployeeBankAccount two = new EmployeeBankAccount();
        two.setBankCode("TESTBANK");
        two.setAccountNo("222222222");
        when(accounts.findByEmployeeId(firstId)).thenReturn(List.of(one));
        when(accounts.findByEmployeeId(secondId)).thenReturn(List.of(two));

        BankFile source = new BankFile();
        source.setId(sourceId);
        source.setRunId(runId);
        source.setBankTemplateId(templateId);
        when(files.findById(sourceId)).thenReturn(Optional.of(source));
        when(runs.lockForSettlement(runId)).thenReturn(runs.findById(runId));
        when(imports.existsByBankFileId(sourceId)).thenReturn(true);
        BankCreditResult accepted = new BankCreditResult();
        accepted.setEmployeeId(firstId);
        accepted.setCreditStatus("ACCEPTED");
        BankCreditResult rejected = new BankCreditResult();
        rejected.setEmployeeId(secondId);
        rejected.setCreditStatus("REJECTED");
        when(creditResults.findByBankFileId(sourceId)).thenReturn(List.of(accepted, rejected));

        BankFileService.Result result = service.recoverRejected(sourceId);
        assertEquals(1, result.file().getRecordCount());
        assertEquals(sourceId, result.file().getRecoverySourceFileId());
        assertEquals(0,new BigDecimal("7500.00").compareTo(result.file().getTotalAmount()));
        assertTrue(result.file().getContent().contains("EMP-1002,222222222,7500.00"));
        assertFalse(result.file().getContent().contains("EMP-1001"));
        when(files.existsByRecoverySourceFileId(sourceId)).thenReturn(true);
        assertThrows(BusinessException.class, () -> service.recoverRejected(sourceId));
    }

    @Test
    void draftRunCannotProduceBankPaymentFile() {
        PayrollRun draft = new PayrollRun();
        draft.setStatus("DRAFT");
        when(runs.findById(runId)).thenReturn(Optional.of(draft));
        assertThrows(BusinessException.class, () -> service.generate(runId, templateId));
        verifyNoInteractions(files);
    }

    private static PayrollEmployee payrollEmployee(UUID employeeId, String amount) {
        PayrollEmployee pe = new PayrollEmployee();
        pe.setEmployeeId(employeeId);
        pe.setNetPay(new BigDecimal(amount));
        return pe;
    }

    private static Employee employee(UUID id, String number) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setEmployeeNo(number);
        employee.setFirstName("Maria");
        employee.setLastName("Santos");
        return employee;
    }
}
