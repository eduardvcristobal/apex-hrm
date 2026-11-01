package com.apex.hrm.personnel.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.personnel.domain.EmployeeDocument;
import com.apex.hrm.personnel.repo.EmployeeDocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/employee-documents")
public class EmployeeDocumentController extends CrudController<EmployeeDocument> {
    public EmployeeDocumentController(EmployeeDocumentRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
