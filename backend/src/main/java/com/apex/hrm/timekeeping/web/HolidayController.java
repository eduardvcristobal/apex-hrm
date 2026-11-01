package com.apex.hrm.timekeeping.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.timekeeping.domain.Holiday;
import com.apex.hrm.timekeeping.repo.HolidayRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/holidays", "/api/holidaies"})
public class HolidayController extends CrudController<Holiday> {
    public HolidayController(HolidayRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
