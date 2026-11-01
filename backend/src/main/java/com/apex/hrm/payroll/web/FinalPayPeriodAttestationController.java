package com.apex.hrm.payroll.web;

import com.apex.hrm.payroll.service.FinalPayPeriodAttestationService;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payroll/final-pay/period-attestations")
public class FinalPayPeriodAttestationController {
    private final FinalPayPeriodAttestationService service;
    public FinalPayPeriodAttestationController(FinalPayPeriodAttestationService service){
        this.service=service;
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public FinalPayPeriodAttestationService.Attestation submit(
      @AuthenticationPrincipal HrmUserPrincipal user,
      @RequestBody FinalPayPeriodAttestationService.Request request){
        return service.submit(user,request);
    }
    @PostMapping("/{id}/review")
    public FinalPayPeriodAttestationService.Attestation review(
      @AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID id){
        return service.decide(user,id,true);
    }
    @PostMapping("/{id}/reject")
    public FinalPayPeriodAttestationService.Attestation reject(
      @AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID id){
        return service.decide(user,id,false);
    }
    @GetMapping
    public List<FinalPayPeriodAttestationService.Attestation> list(
      @AuthenticationPrincipal HrmUserPrincipal user,@RequestParam UUID settlementId){
        return service.list(user,settlementId);
    }
}
