package com.apex.hrm.payroll.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.apex.hrm.common.BusinessException;
import com.apex.hrm.payroll.domain.PayrollLine;
import com.apex.hrm.payroll.domain.WithholdingTaxBracket;
import com.apex.hrm.payroll.repo.PayrollLineRepository;
import com.apex.hrm.payroll.repo.WithholdingTaxBracketRepository;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnnualTaxReconciliationServiceTest {
    private PayrollLine line(String code, String category, String amount, boolean taxable) {
        var result = new PayrollLine();
        result.setItemCode(code);
        result.setCategory(category);
        result.setAmount(new BigDecimal(amount));
        result.setTaxable(taxable);
        return result;
    }
    private WithholdingTaxBracket bracket(String lower, String tax, String rate) {
        var b = new WithholdingTaxBracket();
        b.setEffectiveFrom(LocalDate.of(2023, 1, 1));
        b.setPeriodType("ANNUAL");
        b.setLowerBound(new BigDecimal(lower));
        b.setBaseTax(new BigDecimal(tax));
        b.setRate(new BigDecimal(rate));
        return b;
    }
    @Test void readsOnlyFinalizedPayrollAndComputesAnnualShortfall() {
        PayrollLineRepository lines = mock(PayrollLineRepository.class);
        WithholdingTaxBracketRepository brackets = mock(WithholdingTaxBracketRepository.class);
        EmployeeRepository employees = mock(EmployeeRepository.class);
        UUID employee = UUID.randomUUID();
        when(employees.existsById(employee)).thenReturn(true);
        when(lines.lockedLinesForTaxYear(eq(employee), eq(LocalDate.of(2026,1,1)),
                eq(LocalDate.of(2026,12,31)))).thenReturn(List.of(
                        line("BASIC", "EARNING", "300000", true),
                        line("THIRTEENTH_MONTH", "EARNING", "100000", false),
                        line("OTHER_BENEFIT", "EARNING", "10000", false),
                        line("WTAX", "DEDUCTION", "5000", false)));
        when(brackets.findByPeriodTypeAndEffectiveFromLessThanEqual(
                eq("ANNUAL"), eq(LocalDate.of(2026,12,31)))).thenReturn(List.of(
                        bracket("0","0","0"), bracket("250000","0","0.15"),
                        bracket("400000","22500","0.20")));
        var service = new AnnualTaxReconciliationService(lines, brackets, employees);
        var result = service.preview(employee, 2026);
        assertEquals(new BigDecimal("90000.00"), result.tax().exemptBenefits());
        assertEquals(new BigDecimal("20000.00"), result.tax().taxableBenefits());
        assertEquals(new BigDecimal("320000.00"), result.tax().annualTaxableIncome());
        assertEquals(new BigDecimal("10500.00"), result.tax().annualTax());
        assertEquals(new BigDecimal("5500.00"), result.tax().additionalWithholdingOrRefund());
        assertEquals(4, result.lineCount());
        verify(lines).lockedLinesForTaxYear(employee, LocalDate.of(2026,1,1),
                LocalDate.of(2026,12,31));
    }
    @Test void rejectsAbsentYearToDatePayrollRatherThanClaimZeroTax() {
        PayrollLineRepository lines = mock(PayrollLineRepository.class);
        WithholdingTaxBracketRepository brackets = mock(WithholdingTaxBracketRepository.class);
        EmployeeRepository employees = mock(EmployeeRepository.class);
        UUID employee = UUID.randomUUID();
        when(employees.existsById(employee)).thenReturn(true);
        when(lines.lockedLinesForTaxYear(any(),any(),any())).thenReturn(List.of());
        var service = new AnnualTaxReconciliationService(lines, brackets, employees);
        assertThrows(BusinessException.class, () -> service.preview(employee, 2026));
        verifyNoInteractions(brackets);
    }
}
