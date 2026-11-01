package com.apex.hrm.payroll.web;

import com.apex.hrm.common.ReadController;
import com.apex.hrm.payroll.domain.JournalEntryLine;
import com.apex.hrm.payroll.repo.JournalEntryLineRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/journal-entry-lines")
public class JournalEntryLineController extends ReadController<JournalEntryLine> {
    public JournalEntryLineController(JournalEntryLineRepository repo, ObjectMapper mapper) {
        super(repo);
    }
}
