package com.apex.hrm.payroll.web;

import com.apex.hrm.common.ReadController;
import com.apex.hrm.payroll.domain.Payslip;
import com.apex.hrm.payroll.repo.PayslipRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payslips")
public class PayslipController extends ReadController<Payslip> {
    public PayslipController(PayslipRepository repo, ObjectMapper mapper) {
        super(repo);
    }
}
