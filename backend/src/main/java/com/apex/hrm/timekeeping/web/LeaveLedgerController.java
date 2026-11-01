package com.apex.hrm.timekeeping.web;

import com.apex.hrm.common.ReadController;
import com.apex.hrm.timekeeping.domain.LeaveLedger;
import com.apex.hrm.timekeeping.repo.LeaveLedgerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/leave-ledgers")
public class LeaveLedgerController extends ReadController<LeaveLedger> {
    public LeaveLedgerController(LeaveLedgerRepository repo, ObjectMapper mapper) {
        super(repo);
    }
}
