package com.apex.hrm.org.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.org.domain.JobPosition;
import com.apex.hrm.org.repo.JobPositionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/job-positions")
public class JobPositionController extends CrudController<JobPosition> {
    public JobPositionController(JobPositionRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
