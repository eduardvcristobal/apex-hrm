package com.apex.hrm.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds legacy HRM API requests to the tenant carried by an authenticated workspace identity.
 * The caller-supplied X-Tenant-ID header is deliberately ignored.
 */
@Component
public class TenantFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Tenant-ID";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return !p.startsWith("/api/") || p.startsWith("/api/v1/") || p.startsWith("/api/reference");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        UUID tenantId = principal instanceof HrmUserPrincipal user ? user.tenantId() : null;
        if (tenantId == null) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Sign in to a workspace before accessing HRM data");
            return;
        }
        TenantContext.set(tenantId);
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}
