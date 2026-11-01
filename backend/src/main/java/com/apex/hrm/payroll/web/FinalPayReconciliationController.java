package com.apex.hrm.payroll.web;

import com.apex.hrm.payroll.service.FinalPayReconciliationService;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** MFA-guarded review-only preflight. Never enables bank transmission or marks payroll PAID. */
@RestController
@RequestMapping("/api/v1/payroll/final-pay/reconciliation")
public class FinalPayReconciliationController {
    private final FinalPayReconciliationService service;
    public FinalPayReconciliationController(FinalPayReconciliationService service) {
        this.service=service;
    }
    @GetMapping("/settlements/{settlementId}")
    public FinalPayReconciliationService.Preflight preflight(
        @AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID settlementId){
        return service.preflight(user,settlementId);
    }
}
