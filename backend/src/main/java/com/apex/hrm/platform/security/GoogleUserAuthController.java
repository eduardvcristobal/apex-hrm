package com.apex.hrm.platform.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/security")
public class GoogleUserAuthController {
    private static final Logger log=LoggerFactory.getLogger(GoogleUserAuthController.class);
    private final GoogleIdTokenVerifier google;
    private final DatabaseUserDetailsService users;
    private final ApiTokenService tokens;
    private final MfaService mfa;
    private final WorkspaceAuditService audit;

    public GoogleUserAuthController(GoogleIdTokenVerifier google,DatabaseUserDetailsService users,
            ApiTokenService tokens,MfaService mfa,WorkspaceAuditService audit) {
        this.google=google;
        this.users=users;
        this.tokens=tokens;
        this.mfa=mfa;
        this.audit=audit;
    }

    public record GoogleLogin(@NotBlank @Size(max=8192) String idToken) {
        @Override public String toString() { return "GoogleLogin[idToken=[REDACTED]]"; }
    }

    @PostMapping("/google")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiTokenService.Token signIn(@Valid @RequestBody GoogleLogin request,
            @RequestHeader(value="X-TOTP-Code",required=false) String code) {
        String email=google.verifiedEmail(request.idToken());
        HrmUserPrincipal user;
        try {
            user=(HrmUserPrincipal)users.loadUserByUsername(email);
        } catch(UsernameNotFoundException ex) {
            log.warn("Google sign-in rejected: verified Google account has no matching HRM identity");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Google sign-in could not be verified");
        }
        if(!user.active()) {
            log.warn("Google sign-in rejected: matching HRM identity is inactive");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Google sign-in could not be verified");
        }
        boolean mfaEnabled=mfa.isEnabled(user.id());
        // MFA is a login challenge only after the user enrolls an authenticator.
        // Otherwise authorization is determined by the account's assigned roles.
        if(mfaEnabled) {
            if(code==null || code.isBlank())
                throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"Authenticator code required");
            mfa.requireVerified(user,code);
        }
        ApiTokenService.Token token=tokens.issue(user,mfaEnabled);
        audit.record(user.tenantId(),user.id(),"USER_SIGNED_IN","auth_identity",user.id());
        return token;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> handleSignInError(ResponseStatusException ex) {
        String reason=ex.getReason()==null ? "" : ex.getReason();
        String detail;
        if("Invalid Google identity".equals(reason)) {
            detail="Google could not verify this sign-in. Check that the Google client ID in backend/.env matches the sign-in button and that localhost:5173 is an authorized origin.";
        } else if("Google sign-in could not be verified".equals(reason)) {
            detail="This Google account is not linked to an active HRM profile. Choose the Google email linked to your account, or use username and password.";
        } else if("Authenticator code required".equals(reason)) {
            detail="This account has an authenticator enabled. Enter its current six-digit code to finish signing in.";
        } else if(reason.toLowerCase().contains("authenticator code")) {
            detail="That authenticator code is incorrect or expired. Enter the current six-digit code and try again.";
        } else {
            detail="Google sign-in could not be completed. Please try again.";
        }
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("detail",detail));
    }
}
