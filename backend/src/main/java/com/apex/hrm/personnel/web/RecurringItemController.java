package com.apex.hrm.personnel.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.personnel.domain.RecurringItem;
import com.apex.hrm.personnel.repo.RecurringItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recurring-items")
public class RecurringItemController extends CrudController<RecurringItem> {
    public RecurringItemController(RecurringItemRepository repo, ObjectMapper mapper) {
        super(repo, mapper);
    }
}
