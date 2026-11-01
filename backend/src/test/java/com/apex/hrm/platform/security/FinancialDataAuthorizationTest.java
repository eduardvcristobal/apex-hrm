package com.apex.hrm.platform.security;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.junit.jupiter.api.Assertions.*;

/** Bank-account administrative endpoints use the personnel RBAC/MFA policy. */
class FinancialDataAuthorizationTest {
    private static List<SimpleGrantedAuthority> roles(String... names) {
        return Arrays.stream(names).map(SimpleGrantedAuthority::new).toList();
    }

    @Test
    void onlyAdminOrHrWithMfaCanManageBankAccounts() {
        assertTrue(PersonnelAccessPolicy.personnel(roles("ROLE_TENANT_ADMIN","ROLE_MFA_VERIFIED")));
        assertTrue(PersonnelAccessPolicy.personnel(roles("ROLE_HR_ADMIN","ROLE_MFA_VERIFIED")));
        assertFalse(PersonnelAccessPolicy.personnel(roles("ROLE_TENANT_ADMIN")));
        assertFalse(PersonnelAccessPolicy.personnel(roles("ROLE_HR_ADMIN")));
        assertFalse(PersonnelAccessPolicy.personnel(roles("ROLE_PAYROLL_OFFICER","ROLE_MFA_VERIFIED")));
        assertFalse(PersonnelAccessPolicy.personnel(roles("ROLE_MANAGER","ROLE_MFA_VERIFIED")));
        assertFalse(PersonnelAccessPolicy.personnel(roles("ROLE_EMPLOYEE","ROLE_MFA_VERIFIED")));
        assertFalse(PersonnelAccessPolicy.personnel(roles("ROLE_AUDITOR","ROLE_MFA_VERIFIED")));
        assertFalse(PersonnelAccessPolicy.personnel(roles()));
    }

    @Test
    void payrollExportPermissionRequiresOfficerOrAdminWithMfa() {
        assertTrue(PersonnelAccessPolicy.compensation(roles("ROLE_TENANT_ADMIN","ROLE_MFA_VERIFIED")));
        assertTrue(PersonnelAccessPolicy.compensation(roles("ROLE_PAYROLL_OFFICER","ROLE_MFA_VERIFIED")));
        assertFalse(PersonnelAccessPolicy.compensation(roles("ROLE_PAYROLL_OFFICER")));
        assertFalse(PersonnelAccessPolicy.compensation(roles("ROLE_HR_ADMIN","ROLE_MFA_VERIFIED")));
        assertFalse(PersonnelAccessPolicy.compensation(roles("ROLE_EMPLOYEE","ROLE_MFA_VERIFIED")));
    }
}
