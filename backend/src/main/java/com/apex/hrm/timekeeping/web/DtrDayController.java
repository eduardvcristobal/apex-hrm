package com.apex.hrm.timekeeping.web;

import com.apex.hrm.common.ReadController;
import com.apex.hrm.timekeeping.domain.DtrDay;
import com.apex.hrm.timekeeping.repo.DtrDayRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/dtr-days", "/api/dtr-daies"})
public class DtrDayController extends ReadController<DtrDay> {
    public DtrDayController(DtrDayRepository repo, ObjectMapper mapper) {
        super(repo);
    }
}
