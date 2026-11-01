package com.apex.hrm.timekeeping.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.timekeeping.domain.LeaveType;
import com.apex.hrm.timekeeping.repo.LeaveTypeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/leave-types")
public class LeaveTypeController extends CrudController<LeaveType> {
    public LeaveTypeController(LeaveTypeRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
