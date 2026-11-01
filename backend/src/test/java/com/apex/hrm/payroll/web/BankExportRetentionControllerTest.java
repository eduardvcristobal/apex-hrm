package com.apex.hrm.payroll.web;

import com.apex.hrm.payroll.service.BankExportRetentionService;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BankExportRetentionControllerTest {
    private final BankExportRetentionService service = mock(BankExportRetentionService.class);
    private final BankExportRetentionController controller = new BankExportRetentionController(service);
    private final UUID fileId = UUID.randomUUID();

    private HrmUserPrincipal user(String... roles) {
        return new HrmUserPrincipal(UUID.randomUUID(), UUID.randomUUID(),
            "test@example.test", "", true,
            Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    }

    @Test void managerCannotPurgeEvenWhenMfaVerified() {
        assertThrows(ResponseStatusException.class, () -> controller.purge(fileId,
            user("ROLE_MANAGER", "ROLE_MFA_VERIFIED"),
            new BankExportRetentionController.Action("Approved retention review")));
        verifyNoInteractions(service);
    }

    @Test void payrollOfficerCannotRemoveLegalHold() {
        assertThrows(ResponseStatusException.class, () -> controller.legalHold(fileId,
            user("ROLE_PAYROLL_OFFICER", "ROLE_MFA_VERIFIED"),
            new BankExportRetentionController.HoldAction(false, "Approved legal review")));
        verifyNoInteractions(service);
    }

    @Test void tenantOwnerWithoutMfaCannotReadRetentionMetadata() {
        assertThrows(ResponseStatusException.class, () -> controller.status(fileId,
            user("ROLE_TENANT_ADMIN")));
        verifyNoInteractions(service);
    }

    @Test void tenantOwnerWithMfaMayRequestManualPurge() {
        HrmUserPrincipal principal = user("ROLE_TENANT_ADMIN", "ROLE_MFA_VERIFIED");
        controller.purge(fileId, principal,
            new BankExportRetentionController.Action("Retention committee approval"));
        verify(service).purge(eq(fileId), eq("Retention committee approval"), eq(principal.id()));
    }

    @Test void unauthenticatedPurgeDenied() {
        assertThrows(ResponseStatusException.class, () -> controller.purge(fileId, null,
            new BankExportRetentionController.Action("Approved retention review")));
        verifyNoInteractions(service);
    }
}
