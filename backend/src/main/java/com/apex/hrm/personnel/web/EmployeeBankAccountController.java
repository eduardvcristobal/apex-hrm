package com.apex.hrm.personnel.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.personnel.domain.EmployeeBankAccount;
import com.apex.hrm.personnel.repo.EmployeeBankAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/employee-bank-accounts")
public class EmployeeBankAccountController extends CrudController<EmployeeBankAccount> {
    public EmployeeBankAccountController(EmployeeBankAccountRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
