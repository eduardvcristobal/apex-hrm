package com.apex.hrm.payroll.web;

import com.apex.hrm.payroll.service.FinalPayDocumentVerificationService;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payroll/final-pay/document-verifications")
public class FinalPayDocumentVerificationController {
    private final FinalPayDocumentVerificationService service;
    public FinalPayDocumentVerificationController(FinalPayDocumentVerificationService service){
        this.service=service;
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public FinalPayDocumentVerificationService.Verification verify(
      @AuthenticationPrincipal HrmUserPrincipal user,
      @RequestBody FinalPayDocumentVerificationService.Request input){
        return service.verify(user,input);
    }
    @GetMapping
    public List<FinalPayDocumentVerificationService.Verification> list(
      @AuthenticationPrincipal HrmUserPrincipal user,@RequestParam UUID settlementId){
        return service.list(user,settlementId);
    }
}
