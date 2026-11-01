package com.apex.hrm.platform.web;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.common.TenantContext;
import com.apex.hrm.payroll.service.TenantProvisioningService;
import com.apex.hrm.platform.domain.Tenant;
import com.apex.hrm.platform.repo.TenantRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Legacy tenant management API. Access is denied by SecurityConfig; workspace provisioning uses signup. */
@RestController
@RequestMapping("/api/tenants")
public class TenantController {
    public record CreateTenant(String code, String name) {
    }

    private final TenantRepository tenants;
    private final TenantProvisioningService provisioning;

    public TenantController(TenantRepository tenants, TenantProvisioningService provisioning) {
        this.tenants = tenants;
        this.provisioning = provisioning;
    }

    @GetMapping
    public List<Tenant> list() {
        return tenants.findAll();
    }

    @GetMapping("/{id}")
    public Tenant get(@PathVariable UUID id) {
        return tenants.findById(id).orElseThrow(() -> new NotFoundException("Tenant", id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Tenant create(@RequestBody CreateTenant req) {
        if (req.code() == null || req.code().isBlank() || req.name() == null || req.name().isBlank()) {
            throw new BusinessException("code and name are required");
        }
        Tenant t = new Tenant();
        t.setCode(req.code().trim());
        t.setName(req.name().trim());
        t = tenants.save(t);
        TenantContext.set(t.getId());
        try {
            provisioning.provision();
        } catch (RuntimeException e) {
            tenants.deleteById(t.getId());
            throw e;
        } finally {
            TenantContext.clear();
        }
        return t;
    }
}
