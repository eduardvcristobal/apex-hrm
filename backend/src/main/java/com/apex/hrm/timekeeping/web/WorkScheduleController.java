package com.apex.hrm.timekeeping.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.timekeeping.domain.WorkSchedule;
import com.apex.hrm.timekeeping.repo.WorkScheduleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/work-schedules")
public class WorkScheduleController extends CrudController<WorkSchedule> {
    public WorkScheduleController(WorkScheduleRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
