package com.apex.hrm.payroll.web;

import com.apex.hrm.payroll.service.FinalPayReleaseReadinessService;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payroll/final-pay/release-readiness")
public class FinalPayReleaseReadinessController {
    private final FinalPayReleaseReadinessService service;
    public FinalPayReleaseReadinessController(FinalPayReleaseReadinessService service){
        this.service=service;
    }
    @GetMapping("/settlements/{id}")
    public FinalPayReleaseReadinessService.Readiness review(
      @AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID id){
        return service.preflight(user,id);
    }
}
