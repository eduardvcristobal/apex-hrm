package com.apex.hrm.timekeeping.web;

import com.apex.hrm.common.ReadController;
import com.apex.hrm.timekeeping.domain.OvertimeRequest;
import com.apex.hrm.timekeeping.repo.OvertimeRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/overtime-requests")
public class OvertimeRequestController extends ReadController<OvertimeRequest> {
    public OvertimeRequestController(OvertimeRequestRepository repo, ObjectMapper mapper) {
        super(repo);
    }
}
