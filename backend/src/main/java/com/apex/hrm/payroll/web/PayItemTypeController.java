package com.apex.hrm.payroll.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.payroll.domain.PayItemType;
import com.apex.hrm.payroll.repo.PayItemTypeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pay-item-types")
public class PayItemTypeController extends CrudController<PayItemType> {
    public PayItemTypeController(PayItemTypeRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
