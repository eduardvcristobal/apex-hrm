package com.apex.hrm.payroll.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.payroll.domain.StatutoryContributionRule;
import com.apex.hrm.payroll.repo.StatutoryContributionRuleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reference/statutory-contribution-rules")
public class StatutoryContributionRuleController extends CrudController<StatutoryContributionRule> {
    public StatutoryContributionRuleController(StatutoryContributionRuleRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
