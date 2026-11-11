package com.apex.hrm.timekeeping.web;

import com.apex.hrm.common.ReadController;
import com.apex.hrm.timekeeping.domain.TimeLog;
import com.apex.hrm.timekeeping.repo.TimeLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/time-logs")
public class TimeLogController extends ReadController<TimeLog> {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public TimeLogController(TimeLogRepository repo, ObjectMapper mapper,JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        super(repo);
        this.jdbc=jdbc;this.tenants=tenants;
    }

    @GetMapping("/{id}/photo")
    public ResponseEntity<byte[]> photo(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID id) {
        return tenants.inTenant(user.tenantId(),()->{
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT p.photo_bytes,p.content_type FROM attendance_photo p JOIN time_log l ON l.tenant_id=p.tenant_id AND l.id=p.time_log_id WHERE p.tenant_id=? AND l.id=?",user.tenantId(),id);
            if(rows.isEmpty()) return ResponseEntity.notFound().build();
            MediaType contentType=MediaType.parseMediaType(String.valueOf(rows.get(0).get("content_type")));
            return ResponseEntity.ok().contentType(contentType).cacheControl(CacheControl.noStore()).body((byte[])rows.get(0).get("photo_bytes"));
        });
    }
}
