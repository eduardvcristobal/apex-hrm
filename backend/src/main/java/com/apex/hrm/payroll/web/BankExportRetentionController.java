package com.apex.hrm.payroll.web;

import com.apex.hrm.payroll.service.BankExportRetentionService;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Explicit retention controls; dual control/records retention policy may impose stricter outside approvals. */
@RestController
@RequestMapping("/api/payroll/bank-files")
public class BankExportRetentionController {
    public record Action(@NotBlank @Size(min=8, max=2000) String reason) {}
    public record HoldAction(boolean hold, @NotBlank @Size(min=8, max=2000) String reason) {}

    private final BankExportRetentionService retention;
    public BankExportRetentionController(BankExportRetentionService retention) {
        this.retention = retention;
    }

    private static UUID administrator(HrmUserPrincipal user) {
        if (user == null || !user.isEnabled()
            || user.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_TENANT_ADMIN"))
            || user.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_MFA_VERIFIED"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant administrator with verified MFA required");
        }
        return user.id();
    }

    @GetMapping("/{fileId}/retention")
    public BankExportRetentionService.Status status(@PathVariable UUID fileId,
            @AuthenticationPrincipal HrmUserPrincipal user) {
        administrator(user);
        return retention.status(fileId);
    }

    @PostMapping("/{fileId}/legal-hold")
    public BankExportRetentionService.Status legalHold(@PathVariable UUID fileId,
            @AuthenticationPrincipal HrmUserPrincipal user, @Valid @RequestBody HoldAction action) {
        return retention.setLegalHold(fileId, action.hold(), action.reason(), administrator(user));
    }

    @PostMapping("/{fileId}/purge")
    public BankExportRetentionService.Status purge(@PathVariable UUID fileId,
            @AuthenticationPrincipal HrmUserPrincipal user, @Valid @RequestBody Action action) {
        return retention.purge(fileId, action.reason(), administrator(user));
    }
}
