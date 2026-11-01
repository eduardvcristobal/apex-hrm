package com.apex.hrm.org.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.org.domain.CostCenter;
import com.apex.hrm.org.repo.CostCenterRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cost-centers")
public class CostCenterController extends CrudController<CostCenter> {
    public CostCenterController(CostCenterRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
