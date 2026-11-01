package com.apex.hrm.payroll.web;

import com.apex.hrm.payroll.service.FinalPayBankEvidenceService;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Manual evidence review, not a banking or money movement API. */
@RestController
@RequestMapping("/api/v1/payroll/final-pay/bank-evidence")
public class FinalPayBankEvidenceController {
    private final FinalPayBankEvidenceService service;
    public FinalPayBankEvidenceController(FinalPayBankEvidenceService service){this.service=service;}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public FinalPayBankEvidenceService.Evidence submit(@AuthenticationPrincipal HrmUserPrincipal user,
        @RequestBody FinalPayBankEvidenceService.Submission request){return service.submit(user,request);}
    @PostMapping("/{id}/review")
    public FinalPayBankEvidenceService.Evidence review(@AuthenticationPrincipal HrmUserPrincipal user,
        @PathVariable UUID id){return service.decide(user,id,true);}
    @PostMapping("/{id}/reject")
    public FinalPayBankEvidenceService.Evidence reject(@AuthenticationPrincipal HrmUserPrincipal user,
        @PathVariable UUID id){return service.decide(user,id,false);}
    @GetMapping
    public List<FinalPayBankEvidenceService.Evidence> list(@AuthenticationPrincipal HrmUserPrincipal user,
        @RequestParam UUID paymentAuthorizationId){return service.list(user,paymentAuthorizationId);}
}
