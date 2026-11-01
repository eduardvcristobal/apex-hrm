package com.apex.hrm.payroll.web;

import com.apex.hrm.payroll.service.PayrollIdentityLinkService;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** MFA-protected identity mapping; no implicit employee auto-match. */
@RestController
@RequestMapping("/api/v1/payroll/identity-links")
public class PayrollIdentityLinkController {
    private final PayrollIdentityLinkService service;
    public PayrollIdentityLinkController(PayrollIdentityLinkService service){this.service=service;}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public PayrollIdentityLinkService.Identity request(@AuthenticationPrincipal HrmUserPrincipal user,
            @RequestBody PayrollIdentityLinkService.Submission form) {return service.request(user,form);}
    @PostMapping("/{id}/approve")
    public PayrollIdentityLinkService.Identity approve(@AuthenticationPrincipal HrmUserPrincipal user,
            @PathVariable UUID id){return service.decide(user,id,true);}
    @PostMapping("/{id}/reject")
    public PayrollIdentityLinkService.Identity reject(@AuthenticationPrincipal HrmUserPrincipal user,
            @PathVariable UUID id){return service.decide(user,id,false);}
    @GetMapping
    public List<PayrollIdentityLinkService.Identity> list(@AuthenticationPrincipal HrmUserPrincipal user,
            @RequestParam UUID personnelEmployeeId){return service.list(user,personnelEmployeeId);}
}
