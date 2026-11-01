package com.apex.hrm.platform.service;

import com.apex.hrm.platform.domain.AuditLog;
import com.apex.hrm.platform.repo.AuditLogRepository;
import java.util.UUID;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.security.PlatformAdminPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Append-only trail for pay-affecting actions. */
@Service
public class AuditService {
    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void log(String entityType, UUID entityId, String action, String summary) {
        AuditLog a = new AuditLog();
        a.setEntityType(entityType);
        a.setEntityId(entityId);
        a.setAction(action);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        String actor = principal instanceof HrmUserPrincipal user ? user.email()
                : principal instanceof PlatformAdminPrincipal admin ? admin.email() : "system";
        a.setActor(actor);
        a.setSummary(summary);
        repo.save(a);
    }
}
