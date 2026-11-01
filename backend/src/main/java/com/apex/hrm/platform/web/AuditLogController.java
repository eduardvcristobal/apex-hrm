package com.apex.hrm.platform.web;

import com.apex.hrm.common.ReadController;
import com.apex.hrm.platform.domain.AuditLog;
import com.apex.hrm.platform.repo.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController extends ReadController<AuditLog> {
    public AuditLogController(AuditLogRepository repo, ObjectMapper mapper) {
        super(repo);
    }
}
