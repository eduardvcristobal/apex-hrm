package com.apex.hrm.payroll.service;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.payroll.domain.PayPeriod;
import com.apex.hrm.payroll.domain.Payslip;
import com.apex.hrm.payroll.domain.PayrollEmployee;
import com.apex.hrm.payroll.domain.PayrollLine;
import com.apex.hrm.payroll.domain.PayrollRun;
import com.apex.hrm.payroll.repo.PayslipRepository;
import com.apex.hrm.personnel.domain.Employee;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A payslip is an immutable JSON snapshot of the locked run, never recomputed from live data. */
@Service
public class PayslipService {
    private final PayslipRepository payslips;
    private final EmployeeRepository employees;
    private final ObjectMapper mapper;

    public PayslipService(PayslipRepository payslips, EmployeeRepository employees, ObjectMapper mapper) {
        this.payslips = payslips;
        this.employees = employees;
        this.mapper = mapper;
    }

    @Transactional
    public Payslip issue(PayrollRun run, PayPeriod period, PayrollEmployee pe, List<PayrollLine> lines) {
        Employee emp = employees.findById(pe.getEmployeeId()).orElseThrow();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("employeeNo", emp.getEmployeeNo());
        doc.put("employeeName", (emp.getLastName() + ", " + emp.getFirstName()).trim());
        doc.put("runType", run.getRunType());
        doc.put("periodStart", period.getStartDate());
        doc.put("periodEnd", period.getEndDate());
        doc.put("payDate", period.getPayDate());
        doc.put("earnings", view(lines, "EARNING"));
        doc.put("deductions", view(lines, "DEDUCTION"));
        doc.put("gross", pe.getGross());
        doc.put("totalDeductions", pe.getTotalDeductions());
        doc.put("netPay", pe.getNetPay());
        doc.put("warnings", pe.getWarnings());

        Payslip p = new Payslip();
        p.setPayrollEmployeeId(pe.getId());
        p.setEmployeeId(pe.getEmployeeId());
        p.setRunId(run.getId());
        p.setVersionNo(payslips.findByPayrollEmployeeIdOrderByVersionNoDesc(pe.getId()).stream()
                .findFirst().map(x -> x.getVersionNo() + 1).orElse(1));
        try {
            p.setSnapshot(mapper.writeValueAsString(doc));
        } catch (JsonProcessingException e) {
            throw new BusinessException("Could not build payslip snapshot");
        }
        p.setIssuedAt(Instant.now());
        return payslips.save(p);
    }

    private static List<Map<String, Object>> view(List<PayrollLine> lines, String category) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PayrollLine l : lines) {
            if (category.equals(l.getCategory())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", l.getItemCode());
                m.put("description", l.getDescription());
                m.put("amount", l.getAmount());
                out.add(m);
            }
        }
        return out;
    }
}
