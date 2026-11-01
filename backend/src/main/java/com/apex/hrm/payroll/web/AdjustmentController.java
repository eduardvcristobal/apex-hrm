package com.apex.hrm.payroll.web;

import com.apex.hrm.common.ReadController;
import com.apex.hrm.payroll.domain.Adjustment;
import com.apex.hrm.payroll.repo.AdjustmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/adjustments")
public class AdjustmentController extends ReadController<Adjustment> {
    public AdjustmentController(AdjustmentRepository repo, ObjectMapper mapper) {
        super(repo);
    }
}
