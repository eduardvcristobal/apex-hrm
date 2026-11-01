package com.apex.hrm.payroll.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.payroll.domain.PayPeriod;
import com.apex.hrm.payroll.repo.PayPeriodRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pay-periods")
public class PayPeriodController extends CrudController<PayPeriod> {
    public PayPeriodController(PayPeriodRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
