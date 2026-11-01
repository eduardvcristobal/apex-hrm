package com.apex.hrm.payroll.web;

import com.apex.hrm.common.ReadController;
import com.apex.hrm.payroll.domain.PayrollEmployee;
import com.apex.hrm.payroll.repo.PayrollEmployeeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payroll-employees")
public class PayrollEmployeeController extends ReadController<PayrollEmployee> {
    public PayrollEmployeeController(PayrollEmployeeRepository repo, ObjectMapper mapper) {
        super(repo);
    }
}
