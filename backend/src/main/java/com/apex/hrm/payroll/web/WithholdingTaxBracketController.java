package com.apex.hrm.payroll.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.payroll.domain.WithholdingTaxBracket;
import com.apex.hrm.payroll.repo.WithholdingTaxBracketRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reference/withholding-tax-brackets")
public class WithholdingTaxBracketController extends CrudController<WithholdingTaxBracket> {
    public WithholdingTaxBracketController(WithholdingTaxBracketRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
