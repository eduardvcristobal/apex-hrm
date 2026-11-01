package com.apex.hrm.payroll.web;

import com.apex.hrm.payroll.service.FinalPayPaymentAuthorizationService;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Payroll/MFA authorization only. Never sends money or generates an executable bank file. */
@RestController
@RequestMapping("/api/v1/payroll/final-pay/payment-authorizations")
public class FinalPayPaymentAuthorizationController {
    private final FinalPayPaymentAuthorizationService service;
    public FinalPayPaymentAuthorizationController(FinalPayPaymentAuthorizationService service) {
        this.service=service;
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public FinalPayPaymentAuthorizationService.Authorization prepare(@AuthenticationPrincipal HrmUserPrincipal user,
        @RequestBody FinalPayPaymentAuthorizationService.Prepare request){
        return service.prepare(user,request);
    }
    @PostMapping("/{id}/authorize")
    public FinalPayPaymentAuthorizationService.Authorization authorize(@AuthenticationPrincipal HrmUserPrincipal user,
        @PathVariable UUID id){return service.decide(user,id,true);}
    @PostMapping("/{id}/cancel")
    public FinalPayPaymentAuthorizationService.Authorization cancel(@AuthenticationPrincipal HrmUserPrincipal user,
        @PathVariable UUID id){return service.decide(user,id,false);}
    @GetMapping
    public List<FinalPayPaymentAuthorizationService.Authorization> list(@AuthenticationPrincipal HrmUserPrincipal user,
        @RequestParam UUID settlementId){return service.list(user,settlementId);}
}
