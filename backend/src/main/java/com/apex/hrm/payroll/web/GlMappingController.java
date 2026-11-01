package com.apex.hrm.payroll.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.payroll.domain.GlMapping;
import com.apex.hrm.payroll.repo.GlMappingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/gl-mappings")
public class GlMappingController extends CrudController<GlMapping> {
    public GlMappingController(GlMappingRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
