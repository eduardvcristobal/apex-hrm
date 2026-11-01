package com.apex.hrm.timekeeping.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.timekeeping.domain.EmployeeSchedule;
import com.apex.hrm.timekeeping.repo.EmployeeScheduleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/employee-schedules")
public class EmployeeScheduleController extends CrudController<EmployeeSchedule> {
    public EmployeeScheduleController(EmployeeScheduleRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
