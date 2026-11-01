package com.apex.hrm.org.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.org.domain.Company;
import com.apex.hrm.org.repo.CompanyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies")
public class CompanyController extends CrudController<Company> {
    public CompanyController(CompanyRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
