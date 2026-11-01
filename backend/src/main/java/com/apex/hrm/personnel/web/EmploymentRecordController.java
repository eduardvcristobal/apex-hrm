package com.apex.hrm.personnel.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.personnel.domain.EmploymentRecord;
import com.apex.hrm.personnel.repo.EmploymentRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/employment-records")
public class EmploymentRecordController extends CrudController<EmploymentRecord> {
    public EmploymentRecordController(EmploymentRecordRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
