package com.apex.hrm.payroll.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.payroll.domain.BankTemplate;
import com.apex.hrm.payroll.repo.BankTemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bank-templates")
public class BankTemplateController extends CrudController<BankTemplate> {
    public BankTemplateController(BankTemplateRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
