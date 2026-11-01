package com.apex.hrm.platform.company;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController("saasCompanyController")
@RequestMapping("/api/v1/companies")
public class CompanyController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public CompanyController(JdbcTemplate jdbc, TenantJdbcExecutor tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }
    public record Company(UUID id, String companyCode, String legalName, String countryCode, String timezone,
            String tradeName, String entityType, String registrationNumber, String ownerName, String ownerEmail,
            String ownerPhone, String addressLine1, String addressLine2, String city, String region,
            String postalCode, String industry) {}
    public record CreateCompany(
        @NotBlank @Size(max=40) @Pattern(regexp="[A-Za-z0-9_-]+") String companyCode,
        @NotBlank @Size(max=200) String legalName,
        @Size(max=200) String tradeName, @Size(max=40) String entityType,
        @Size(max=100) String registrationNumber, @Size(max=200) String ownerName,
        @Email @Size(max=254) String ownerEmail, @Size(max=40) String ownerPhone,
        @Size(max=200) String addressLine1, @Size(max=200) String addressLine2,
        @Size(max=120) String city, @Size(max=120) String region, @Size(max=20) String postalCode,
        @Pattern(regexp="[A-Z]{2}") String countryCode, @Size(max=64) String timezone,
        @Size(max=120) String industry) {}
    public record UpdateCompany(
        @NotBlank @Size(max=40) @Pattern(regexp="[A-Za-z0-9_-]+") String companyCode,
        @NotBlank @Size(max=200) String legalName,
        @Size(max=200) String tradeName, @Size(max=40) String entityType,
        @Size(max=100) String registrationNumber, @Size(max=200) String ownerName,
        @Email @Size(max=254) String ownerEmail, @Size(max=40) String ownerPhone,
        @Size(max=200) String addressLine1, @Size(max=200) String addressLine2,
        @Size(max=120) String city, @Size(max=120) String region, @Size(max=20) String postalCode,
        @NotBlank @Pattern(regexp="[A-Z]{2}") String countryCode,
        @NotBlank @Size(max=64) String timezone, @Size(max=120) String industry) {}

    @GetMapping
    public List<Company> list(@AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(), () -> jdbc.query("""
            SELECT id,company_code,legal_name,country_code,timezone,trade_name,entity_type,registration_number,
                   owner_name,owner_email,owner_phone,address_line1,address_line2,city,region,postal_code,industry
            FROM company WHERE tenant_id = ? ORDER BY company_code
            """, (rs,i) -> map(rs), user.tenantId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Company create(@AuthenticationPrincipal HrmUserPrincipal user, @Valid @RequestBody CreateCompany input) {
        return tenants.inTenant(user.tenantId(), () -> {
            var result = jdbc.queryForObject("""
                INSERT INTO company(tenant_id,company_code,legal_name,trade_name,entity_type,registration_number,
                    owner_name,owner_email,owner_phone,address_line1,address_line2,city,region,postal_code,country_code,timezone,industry)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                RETURNING id,company_code,legal_name,country_code,timezone,trade_name,entity_type,registration_number,
                    owner_name,owner_email,owner_phone,address_line1,address_line2,city,region,postal_code,industry
                """, (rs,i) -> map(rs),
                user.tenantId(),input.companyCode(),input.legalName().trim(),clean(input.tradeName()),clean(input.entityType()),
                clean(input.registrationNumber()),clean(input.ownerName()),clean(input.ownerEmail()),clean(input.ownerPhone()),
                clean(input.addressLine1()),clean(input.addressLine2()),clean(input.city()),clean(input.region()),clean(input.postalCode()),
                input.countryCode()==null||input.countryCode().isBlank()?"PH":input.countryCode(),
                input.timezone()==null||input.timezone().isBlank()?"Asia/Manila":input.timezone(),clean(input.industry()));
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'COMPANY_CREATED','company',?)
                """,user.tenantId(),user.id(),result.id());
            return result;
        });
    }

    @PutMapping("/{companyId}")
    public Company update(@PathVariable UUID companyId, @AuthenticationPrincipal HrmUserPrincipal user,
                          @Valid @RequestBody UpdateCompany input) {
        return tenants.inTenant(user.tenantId(), () -> {
            var rows = jdbc.query("""
                UPDATE company SET company_code=?,legal_name=?,trade_name=?,entity_type=?,registration_number=?,
                    owner_name=?,owner_email=?,owner_phone=?,address_line1=?,address_line2=?,city=?,region=?,postal_code=?,
                    country_code=?,timezone=?,industry=?
                WHERE tenant_id=? AND id=?
                RETURNING id,company_code,legal_name,country_code,timezone,trade_name,entity_type,registration_number,
                    owner_name,owner_email,owner_phone,address_line1,address_line2,city,region,postal_code,industry
                """, (rs,i) -> map(rs),input.companyCode(),input.legalName().trim(),clean(input.tradeName()),clean(input.entityType()),
                clean(input.registrationNumber()),clean(input.ownerName()),clean(input.ownerEmail()),clean(input.ownerPhone()),
                clean(input.addressLine1()),clean(input.addressLine2()),clean(input.city()),clean(input.region()),clean(input.postalCode()),
                input.countryCode(),input.timezone(),clean(input.industry()),user.tenantId(),companyId);
            if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND,"Company not found");
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'COMPANY_UPDATED','company',?)
                """,user.tenantId(),user.id(),companyId);
            return rows.get(0);
        });
    }

    private static Company map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Company(rs.getObject("id",UUID.class),rs.getString("company_code"),rs.getString("legal_name"),
            rs.getString("country_code"),rs.getString("timezone"),rs.getString("trade_name"),rs.getString("entity_type"),
            rs.getString("registration_number"),rs.getString("owner_name"),rs.getString("owner_email"),rs.getString("owner_phone"),
            rs.getString("address_line1"),rs.getString("address_line2"),rs.getString("city"),rs.getString("region"),
            rs.getString("postal_code"),rs.getString("industry"));
    }
    private static String clean(String value) { return value==null||value.isBlank()?null:value.trim(); }
}
