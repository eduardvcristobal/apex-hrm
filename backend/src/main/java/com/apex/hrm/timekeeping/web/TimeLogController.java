package com.apex.hrm.timekeeping.web;

import com.apex.hrm.common.ReadController;
import com.apex.hrm.timekeeping.domain.TimeLog;
import com.apex.hrm.timekeeping.repo.TimeLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/time-logs")
public class TimeLogController extends ReadController<TimeLog> {
    public TimeLogController(TimeLogRepository repo, ObjectMapper mapper) {
        super(repo);
    }
}
