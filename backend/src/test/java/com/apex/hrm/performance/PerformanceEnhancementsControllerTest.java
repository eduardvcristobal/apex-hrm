package com.apex.hrm.performance;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PerformanceEnhancementsControllerTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final TenantJdbcExecutor tenants=mock(TenantJdbcExecutor.class);
    private final PerformanceEnhancementsController controller=new PerformanceEnhancementsController(jdbc,tenants);
    private HrmUserPrincipal actor(String... roles) {
        return new HrmUserPrincipal(UUID.randomUUID(),UUID.randomUUID(),"actor@example.test","",true,
            Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    }
    @Test void employeeCannotEditGoals() {
        assertThrows(ResponseStatusException.class,()->controller.editGoal(actor("ROLE_EMPLOYEE","ROLE_MFA_VERIFIED"),
            UUID.randomUUID(),new PerformanceEnhancementsController.GoalEdit("Target",null,null,BigDecimal.TEN,BigDecimal.ONE,null)));
        verifyNoInteractions(jdbc,tenants);
    }
    @Test void managerCannotReadHrReport() {
        assertThrows(ResponseStatusException.class,()->controller.summary(
            actor("ROLE_MANAGER","ROLE_MFA_VERIFIED"),UUID.randomUUID()));
        verifyNoInteractions(jdbc,tenants);
    }
    @Test void unauthenticatedCannotReadMilestones() {
        assertThrows(ResponseStatusException.class,()->controller.milestones(null,UUID.randomUUID()));
        verifyNoInteractions(jdbc,tenants);
    }
    @Test void missingMfaCannotUpdateMilestoneEvidence() {
        assertThrows(ResponseStatusException.class,()->controller.updateMilestone(
            actor("ROLE_HR_ADMIN"),UUID.randomUUID(),
            new PerformanceEnhancementsController.MilestoneUpdate("COMPLETED","completed evidence")));
        verifyNoInteractions(jdbc,tenants);
    }
}
