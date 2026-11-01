package com.apex.hrm.payroll.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.payroll.domain.GlAccount;
import com.apex.hrm.payroll.repo.GlAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/gl-accounts")
public class GlAccountController extends CrudController<GlAccount> {
    public GlAccountController(GlAccountRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
