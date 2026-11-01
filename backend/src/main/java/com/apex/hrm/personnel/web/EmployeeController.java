package com.apex.hrm.personnel.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.personnel.domain.Employee;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController extends CrudController<Employee> {
    public EmployeeController(EmployeeRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
