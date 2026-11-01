package com.apex.hrm.payroll.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.payroll.domain.*;
import com.apex.hrm.payroll.repo.*;
import com.apex.hrm.personnel.domain.Employee;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.platform.service.AuditService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BankCreditResultServiceTest {
    private final PayrollRunRepository runs = mock(PayrollRunRepository.class);
    private final BankFileRepository files = mock(BankFileRepository.class);
    private final BankTemplateRepository templates = mock(BankTemplateRepository.class);
    private final PayrollEmployeeRepository payrollEmployees = mock(PayrollEmployeeRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final BankCreditImportRepository imports = mock(BankCreditImportRepository.class);
    private final BankCreditResultRepository results = mock(BankCreditResultRepository.class);
    private final PayrollBankSettlementRepository settlements = mock(PayrollBankSettlementRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final BankCreditResultService service = new BankCreditResultService(runs, files, templates,
            payrollEmployees, employees, imports, results, settlements, audit);
    private final UUID runId = UUID.randomUUID(), fileId = UUID.randomUUID(), templateId = UUID.randomUUID();
    private final UUID first = UUID.randomUUID(), second = UUID.randomUUID(), actor = UUID.randomUUID();

    @BeforeEach void setup() {
        PayrollRun run = new PayrollRun();
        run.setId(runId);
        run.setStatus("LOCKED");
        when(runs.lockForSettlement(runId)).thenReturn(Optional.of(run));
        BankFile file = new BankFile();
        file.setId(fileId); file.setRunId(runId); file.setBankTemplateId(templateId);
        file.setContent("EMPLOYEE_NO,ACCOUNT_NO,AMOUNT\nEMP-1,111,100.00\nEMP-2,222,200.00\n");
        file.setRecordCount(2);
        file.setTotalAmount(new BigDecimal("300.00"));
        when(files.findById(fileId)).thenReturn(Optional.of(file));
        BankTemplate t = new BankTemplate();
        t.setColumnSpec("EMPLOYEE_NO,ACCOUNT_NO,AMOUNT");
        when(templates.findById(templateId)).thenReturn(Optional.of(t));
        when(payrollEmployees.findByRunId(runId)).thenReturn(List.of(pay(first,"100.00"), pay(second,"200.00")));
        when(employees.findById(first)).thenReturn(Optional.of(employee("EMP-1")));
        when(employees.findById(second)).thenReturn(Optional.of(employee("EMP-2")));
        when(imports.saveAndFlush(any(BankCreditImport.class))).thenAnswer(call -> {
            BankCreditImport result = call.getArgument(0); result.setId(UUID.randomUUID()); return result;
        });
    }
    private static PayrollEmployee pay(UUID id,String net) {
        PayrollEmployee e=new PayrollEmployee();e.setEmployeeId(id);e.setNetPay(new BigDecimal(net));return e;
    }
    private static Employee employee(String no) {
        Employee e=new Employee();e.setEmployeeNo(no);return e;
    }

    @Test void partialBankRejectionImportsExactPerEmployeeTotals() {
        var imported = service.importResults(fileId,
                "EMPLOYEE_NO,AMOUNT,STATUS,REFERENCE\nEMP-1,100.00,ACCEPTED,OK-1\nEMP-2,200.00,REJECTED,BAD-2",
                "BANK-STATEMENT-2026",actor);
        assertEquals(1, imported.accepted());
        assertEquals(1, imported.rejected());
        assertEquals(0,new BigDecimal("100.00").compareTo(imported.acceptedAmount()));
        assertEquals(0,new BigDecimal("200.00").compareTo(imported.rejectedAmount()));
        ArgumentCaptor<BankCreditResult> saved = ArgumentCaptor.forClass(BankCreditResult.class);
        verify(results,times(2)).save(saved.capture());
        assertEquals(List.of("ACCEPTED","REJECTED"),
                saved.getAllValues().stream().map(BankCreditResult::getCreditStatus).toList());
    }

    @Test void rejectsMissingEmployeeAndForeignEmployee() {
        String heading="EMPLOYEE_NO,AMOUNT,STATUS,REFERENCE\n";
        assertThrows(BusinessException.class,()->service.importResults(fileId,
                heading+"EMP-1,100.00,ACCEPTED,A", "EV",actor));
        assertThrows(BusinessException.class,()->service.importResults(fileId,
                heading+"EMP-1,100.00,ACCEPTED,A\nOTHER,200.00,REJECTED,B", "EV",actor));
        verify(imports,never()).saveAndFlush(any());
    }

    @Test void duplicateAndAmountMismatchFailWithoutWriting() {
        String heading="EMPLOYEE_NO,AMOUNT,STATUS,REFERENCE\n";
        assertThrows(BusinessException.class,()->service.importResults(fileId,
                heading+"EMP-1,100.00,ACCEPTED,A\nEMP-1,200.00,REJECTED,B", "EV",actor));
        assertThrows(BusinessException.class,()->service.importResults(fileId,
                heading+"EMP-1,101.00,ACCEPTED,A\nEMP-2,200.00,REJECTED,B", "EV",actor));
        verify(imports,never()).saveAndFlush(any());
    }

    @Test void duplicateAcceptedEmployeeAcrossBanksAndReimportBlocked() {
        BankCreditResult accepted = new BankCreditResult();
        accepted.setEmployeeId(first);
        accepted.setCreditStatus("ACCEPTED");
        when(results.findByRunIdAndEmployeeIdAndCreditStatus(runId,first,"ACCEPTED")).thenReturn(List.of(accepted));
        String csv="EMPLOYEE_NO,AMOUNT,STATUS,REFERENCE\nEMP-1,100.00,ACCEPTED,A\nEMP-2,200.00,REJECTED,B";
        assertThrows(BusinessException.class,()->service.importResults(fileId,csv,"EV",actor));
        when(results.findByRunIdAndEmployeeIdAndCreditStatus(runId,first,"ACCEPTED")).thenReturn(List.of());
        when(imports.existsByBankFileId(fileId)).thenReturn(true);
        assertThrows(BusinessException.class,()->service.importResults(fileId,csv,"EV",actor));
    }

    @Test void malformedBankResultIsRejected() {
        assertThrows(BusinessException.class,()->service.importResults(fileId,
                "EMPLOYEE_NO,AMOUNT,STATUS,REFERENCE\nEMP-1,100.00,ACCEPTED,A\nEMP-2,200.00,UNKNOWN,B","EV",actor));
        assertThrows(BusinessException.class,()->service.importResults(fileId,
                "EMPLOYEE_NO,AMOUNT,STATUS,REFERENCE\nEMP-1,100.001,ACCEPTED,A\nEMP-2,200.00,REJECTED,B","EV",actor));
        assertThrows(BusinessException.class,()->service.importResults(fileId,
                "EMPLOYEE_NO,AMOUNT,STATUS,REFERENCE\nEMP-1,100.00,ACCEPTED,A\nEMP-2,200.00,REJECTED,B","",actor));
    }

    @Test void csvQuotesAndExactCentsAreStrict() {
        assertEquals(List.of("A,B","100.00","ACCEPTED","R"),
                BankCreditResultService.splitCsvLine("\"A,B\",100.00,ACCEPTED,R",','));
        assertThrows(BusinessException.class,()->BankCreditResultService.splitCsvLine("\"unterminated",','));
        assertThrows(BusinessException.class,()->BankCreditResultService.money("100.001"));
        assertEquals(0,new BigDecimal("100.00").compareTo(BankCreditResultService.money("100.0000")));
    }
}
