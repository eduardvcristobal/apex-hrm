package com.apex.hrm.payroll.web;

import com.apex.hrm.payroll.domain.AnnualTaxAdjustmentRequest;
import com.apex.hrm.payroll.service.AnnualTaxAdjustmentService;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Protected by the payroll MFA/role rules in SecurityConfig. */
@RestController
@RequestMapping("/api/payroll/tax/adjustment-requests")
public class AnnualTaxAdjustmentController {
    private final AnnualTaxAdjustmentService service;
    public AnnualTaxAdjustmentController(AnnualTaxAdjustmentService service) {
        this.service = service;
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AnnualTaxAdjustmentRequest request(@RequestBody AnnualTaxAdjustmentService.Submit form,
            @AuthenticationPrincipal HrmUserPrincipal user) {
        return service.request(form, user.id());
    }
    @GetMapping("/{id}")
    public AnnualTaxAdjustmentRequest read(@PathVariable UUID id) {
        return service.read(id);
    }
    @PostMapping("/{id}/approve")
    public AnnualTaxAdjustmentRequest approve(@PathVariable UUID id,
            @AuthenticationPrincipal HrmUserPrincipal user) {
        return service.approve(id, user.id());
    }
    @PostMapping("/{id}/reject")
    public AnnualTaxAdjustmentRequest reject(@PathVariable UUID id,
            @AuthenticationPrincipal HrmUserPrincipal user) {
        return service.reject(id, user.id());
    }
}
