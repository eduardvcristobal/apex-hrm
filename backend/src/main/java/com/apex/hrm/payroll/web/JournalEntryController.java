package com.apex.hrm.payroll.web;

import com.apex.hrm.common.ReadController;
import com.apex.hrm.payroll.domain.JournalEntry;
import com.apex.hrm.payroll.repo.JournalEntryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/journal-entries")
public class JournalEntryController extends ReadController<JournalEntry> {
    public JournalEntryController(JournalEntryRepository repo, ObjectMapper mapper) {
        super(repo);
    }
}
