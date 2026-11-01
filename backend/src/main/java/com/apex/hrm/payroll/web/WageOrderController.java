package com.apex.hrm.payroll.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.payroll.domain.WageOrder;
import com.apex.hrm.payroll.repo.WageOrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reference/wage-orders")
public class WageOrderController extends CrudController<WageOrder> {
    public WageOrderController(WageOrderRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
