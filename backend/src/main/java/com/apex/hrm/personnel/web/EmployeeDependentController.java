package com.apex.hrm.personnel.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.personnel.domain.EmployeeDependent;
import com.apex.hrm.personnel.repo.EmployeeDependentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/employee-dependents")
public class EmployeeDependentController extends CrudController<EmployeeDependent> {
    public EmployeeDependentController(EmployeeDependentRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
