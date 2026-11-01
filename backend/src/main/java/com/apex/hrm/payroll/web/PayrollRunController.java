package com.apex.hrm.payroll.web;

import com.apex.hrm.common.ReadController;
import com.apex.hrm.payroll.domain.PayrollRun;
import com.apex.hrm.payroll.repo.PayrollRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payroll-runs")
public class PayrollRunController extends ReadController<PayrollRun> {
    public PayrollRunController(PayrollRunRepository repo, ObjectMapper mapper) {
        super(repo);
    }
}
