package com.apex.hrm.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/** Shared PostgreSQL throttle for authentication, recovery, invitation, and MFA requests; proxy headers are not trusted. */
public class LoginThrottleFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final DatabaseUserDetailsService users;
    private final org.springframework.security.crypto.password.PasswordEncoder passwords;
    public LoginThrottleFilter(JdbcTemplate jdbc,DatabaseUserDetailsService users,
         org.springframework.security.crypto.password.PasswordEncoder passwords) {
        this.jdbc=jdbc;this.users=users;this.passwords=passwords;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String key=key(request);
        if(key==null) {chain.doFilter(request,response);return;}
        Integer attempts=jdbc.queryForObject("""
            SELECT COALESCE((SELECT attempts FROM auth_login_attempt
              WHERE key_sha256=? AND window_start>now()-interval '10 minutes'),0)
            """,Integer.class,key);
        if(attempts!=null && attempts>=limit(request)) {
            response.setHeader("Retry-After","600");
            response.setStatus(429);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"title\":\"Too Many Requests\",\"status\":429}");
            return;
        }
        boolean throttledEndpoint=throttledEndpoint(request);
        boolean valid = !throttledEndpoint && validCredentials(request);
        if(valid) {
            jdbc.update("DELETE FROM auth_login_attempt WHERE key_sha256=?",key);
            chain.doFilter(request,response);
            return;
        }
        // Count anonymous high-risk endpoint requests and invalid Basic credentials before
        // invoking Spring Security: authentication failures may exit the chain by exception.
        jdbc.update("""
            INSERT INTO auth_login_attempt(key_sha256,attempts,window_start,updated_at)
            VALUES (?,1,now(),now())
            ON CONFLICT(key_sha256) DO UPDATE
            SET attempts=CASE WHEN auth_login_attempt.window_start<now()-interval '10 minutes'
                 THEN 1 ELSE auth_login_attempt.attempts+1 END,
                window_start=CASE WHEN auth_login_attempt.window_start<now()-interval '10 minutes'
                 THEN now() ELSE auth_login_attempt.window_start END,
                updated_at=now()
            """,key);
        chain.doFilter(request,response);
        // These endpoints are throttled to stop repeated failures. A legitimate
        // signup, invite acceptance, or MFA confirmation must not consume the
        // same failure budget as an invalid attempt from the same client.
        if(response.getStatus()<400) jdbc.update("DELETE FROM auth_login_attempt WHERE key_sha256=?",key);

    }
    private boolean validCredentials(HttpServletRequest request) {
        String auth=request.getHeader("Authorization");
        try {
            String decoded=new String(Base64.getDecoder().decode(auth.substring(6)),StandardCharsets.UTF_8);
            int separator=decoded.indexOf(':');
            if(separator<1) return false;
            String username=decoded.substring(0,separator).toLowerCase(Locale.ROOT);
            String password=decoded.substring(separator+1);
            var user=users.loadUserByUsername(username);
            return user.isEnabled() && passwords.matches(password,user.getPassword());
        } catch(Exception ex) {return false;}
    }
    static String key(HttpServletRequest request) {
        if(throttledEndpoint(request)) {
            try {
                var digest=MessageDigest.getInstance("SHA-256").digest(
                    (request.getRemoteAddr()+"|"+request.getRequestURI()).getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest);
            } catch(Exception ex) {return null;}
        }
        String header=request.getHeader("Authorization");
        if(header==null || !header.regionMatches(true,0,"Basic ",0,6) || header.length()>1024) return null;
        try {
            var raw=new String(Base64.getDecoder().decode(header.substring(6)),StandardCharsets.UTF_8);
            int colon=raw.indexOf(':');
            if(colon<1 || colon>254) return null;
            String user=raw.substring(0,colon).toLowerCase(Locale.ROOT);
            var digest=MessageDigest.getInstance("SHA-256").digest(
                (request.getRemoteAddr()+"|"+user).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch(Exception ex) {return null;}
    }

    private static boolean throttledEndpoint(HttpServletRequest request){
        if(!"POST".equals(request.getMethod())) return false;
        String path=request.getRequestURI();
        return "/api/v1/security/platform-admin/google".equals(path)
                || "/api/v1/security/google".equals(path)
                || "/api/v1/security/signup".equals(path)
                || "/api/v1/security/signup/google".equals(path)
                || "/api/v1/security/password-reset/request".equals(path)
                || "/api/v1/security/password-reset/confirm".equals(path)
                || "/api/v1/invitations/accept".equals(path)
                || "/api/v1/invitations/accept/google".equals(path)
                || "/api/v1/security/mfa/confirm".equals(path)
                || path.startsWith("/api/v1/platform-admin/mfa/");
    }

    private static int limit(HttpServletRequest request){
        String path=request.getRequestURI();
        if("/api/v1/security/password-reset/request".equals(path)) return 5;
        if("/api/v1/security/mfa/confirm".equals(path)||path.startsWith("/api/v1/platform-admin/mfa/")) return 8;
        return 10;
    }
}
