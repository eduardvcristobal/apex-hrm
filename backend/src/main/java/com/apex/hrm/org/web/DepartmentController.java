package com.apex.hrm.org.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.org.domain.Department;
import com.apex.hrm.org.repo.DepartmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/departments")
public class DepartmentController extends CrudController<Department> {
    public DepartmentController(DepartmentRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
