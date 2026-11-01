package com.apex.hrm.payroll.web;

import com.apex.hrm.common.ReadController;
import com.apex.hrm.payroll.domain.BankFile;
import com.apex.hrm.payroll.repo.BankFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bank-files")
public class BankFileController extends ReadController<BankFile> {
    public BankFileController(BankFileRepository repo, ObjectMapper mapper) {
        super(repo);
    }
}
