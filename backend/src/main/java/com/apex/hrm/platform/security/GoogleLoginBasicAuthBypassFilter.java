package com.apex.hrm.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Ignore browser-cached HTTP Basic credentials on the Google credential exchange endpoint. */
final class GoogleLoginBasicAuthBypassFilter extends OncePerRequestFilter {
    private static final String GOOGLE_LOGIN_PATH = "/api/v1/security/google";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && GOOGLE_LOGIN_PATH.equals(request.getServletPath()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpServletRequest withoutBasicCredentials = new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                String value = super.getHeader(name);
                if ("Authorization".equalsIgnoreCase(name) && value != null
                        && value.regionMatches(true, 0, "Basic ", 0, 6)) {
                    return null;
                }
                return value;
            }
        };
        chain.doFilter(withoutBasicCredentials, response);
    }
}
