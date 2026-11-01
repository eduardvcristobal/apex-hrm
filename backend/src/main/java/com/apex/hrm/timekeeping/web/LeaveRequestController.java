package com.apex.hrm.timekeeping.web;

import com.apex.hrm.common.ReadController;
import com.apex.hrm.timekeeping.domain.LeaveRequest;
import com.apex.hrm.timekeeping.repo.LeaveRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/leave-requests")
public class LeaveRequestController extends ReadController<LeaveRequest> {
    public LeaveRequestController(LeaveRequestRepository repo, ObjectMapper mapper) {
        super(repo);
    }
}
