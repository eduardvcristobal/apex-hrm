package com.apex.hrm.common;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;

class TenantFilterSecurityTest {
    private final TenantFilter filter = new TenantFilter();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }

    private void authenticate(UUID tenantId, boolean enabled) {
        HrmUserPrincipal principal = new HrmUserPrincipal(UUID.randomUUID(), tenantId,
            "hr@example.test", "not-a-real-password", enabled, List.of());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    void ignoresSpoofedHeaderAndClearsContextAfterRequest() throws Exception {
        UUID genuineTenant = UUID.randomUUID();
        authenticate(genuineTenant, true);
        MockHttpServletRequest request = request("/api/employees");
        request.addHeader(TenantFilter.HEADER, UUID.randomUUID().toString());
        AtomicReference<UUID> observed = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
            observed.set(TenantContext.get()));
        assertEquals(genuineTenant, observed.get());
        assertNull(TenantContext.get(), "tenant must not leak across pooled request threads");
    }

    @Test
    void rejectsUnauthenticatedRequestsAndClearsStaleTenant() throws Exception {
        TenantContext.set(UUID.randomUUID());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("/api/payroll/runs"), response, (req, res) ->
            fail("unauthenticated request must not execute"));
        assertEquals(401, response.getStatus());
        assertNull(TenantContext.get());
    }

    @Test
    void rejectsDisabledWorkspaceIdentity() throws Exception {
        authenticate(UUID.randomUUID(), false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("/api/employees"), response, (req, res) ->
            fail("disabled identity must not execute"));
        assertEquals(401, response.getStatus());
        assertNull(TenantContext.get());
    }

    @Test
    void platformAdminCannotImpersonateTenantUsingHeader() throws Exception {
        MockHttpServletRequest request = request("/api/payroll/runs");
        request.addHeader(TenantFilter.HEADER, UUID.randomUUID().toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) ->
            fail("tenant must come from workspace principal"));
        assertEquals(401, response.getStatus());
    }

    @Test
    void skipsVersionedApiWithoutSettingTenantContext() throws Exception {
        MockHttpServletRequest request = request("/api/v1/me");
        AtomicReference<UUID> observed = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
            observed.set(TenantContext.get()));
        assertNull(observed.get());
    }
}
