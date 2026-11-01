package com.apex.hrm.org.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.org.domain.WorkLocation;
import com.apex.hrm.org.repo.WorkLocationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/work-locations")
public class WorkLocationController extends CrudController<WorkLocation> {
    public WorkLocationController(WorkLocationRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
