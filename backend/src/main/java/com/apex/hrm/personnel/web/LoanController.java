package com.apex.hrm.personnel.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.personnel.domain.Loan;
import com.apex.hrm.personnel.repo.LoanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/loans")
public class LoanController extends CrudController<Loan> {
    public LoanController(LoanRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
