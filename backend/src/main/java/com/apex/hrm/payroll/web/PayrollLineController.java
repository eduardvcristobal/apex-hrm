package com.apex.hrm.payroll.web;

import com.apex.hrm.common.ReadController;
import com.apex.hrm.payroll.domain.PayrollLine;
import com.apex.hrm.payroll.repo.PayrollLineRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payroll-lines")
public class PayrollLineController extends ReadController<PayrollLine> {
    public PayrollLineController(PayrollLineRepository repo, ObjectMapper mapper) {
        super(repo);
    }
}
