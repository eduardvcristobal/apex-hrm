package com.apex.hrm.timekeeping.web;

import com.apex.hrm.common.ReadController;
import com.apex.hrm.timekeeping.domain.AttendanceCorrection;
import com.apex.hrm.timekeeping.repo.AttendanceCorrectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/attendance-corrections")
public class AttendanceCorrectionController extends ReadController<AttendanceCorrection> {
    public AttendanceCorrectionController(AttendanceCorrectionRepository repo, ObjectMapper mapper) {
        super(repo);
    }
}
