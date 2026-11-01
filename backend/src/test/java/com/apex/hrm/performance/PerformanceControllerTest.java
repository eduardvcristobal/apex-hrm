package com.apex.hrm.performance;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PerformanceControllerTest {
    private final PerformanceController controller = new PerformanceController(null,null);
    private HrmUserPrincipal actor(String... roles) {
        return new HrmUserPrincipal(UUID.randomUUID(),UUID.randomUUID(),"hr@example.test","",
            true,java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    }
    @Test void anonymousIsRejected() {
        assertThrows(ResponseStatusException.class,()->controller.cycles(null,UUID.randomUUID()));
    }
    @Test void hrAdminWithoutMfaIsRejected() {
        assertThrows(ResponseStatusException.class,()->controller.cycles(actor("ROLE_HR_ADMIN"),UUID.randomUUID()));
    }
    @Test void employeeWithMfaCannotManageReviews() {
        assertThrows(ResponseStatusException.class,()->controller.reviews(
           actor("ROLE_EMPLOYEE","ROLE_MFA_VERIFIED"),UUID.randomUUID()));
    }
    @Test void managerWithMfaCannotCreateGoalsWithoutHrDelegation() {
        var user=actor("ROLE_MANAGER","ROLE_MFA_VERIFIED");
        assertThrows(ResponseStatusException.class,()->controller.createGoal(user,null));
    }
}
