package com.apex.hrm.payroll.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.payroll.domain.PayGroup;
import com.apex.hrm.payroll.repo.PayGroupRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pay-groups")
public class PayGroupController extends CrudController<PayGroup> {
    public PayGroupController(PayGroupRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
