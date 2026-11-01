package com.apex.hrm.payroll.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.payroll.domain.PremiumRule;
import com.apex.hrm.payroll.repo.PremiumRuleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reference/premium-rules")
public class PremiumRuleController extends CrudController<PremiumRule> {
    public PremiumRuleController(PremiumRuleRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
