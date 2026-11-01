package com.apex.hrm.payroll.web;

import com.apex.hrm.payroll.service.FinalPaySettlementService;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Settlement approval and review; no transfer, payslip issuance or bank-file action. */
@RestController
@RequestMapping("/api/v1/payroll/final-pay")
public class FinalPaySettlementController {
    private final FinalPaySettlementService service;
    public FinalPaySettlementController(FinalPaySettlementService service) {
        this.service = service;
    }
    @PostMapping("/employees/{employeeId}/cases/{caseId}/handoffs/{handoffId}/settlements")
    @ResponseStatus(HttpStatus.CREATED)
    public FinalPaySettlementService.Settlement submit(@AuthenticationPrincipal HrmUserPrincipal user,
            @PathVariable UUID employeeId, @PathVariable UUID caseId, @PathVariable UUID handoffId,
            @RequestBody FinalPaySettlementService.Submission input) {
        return service.submit(user,employeeId,caseId,handoffId,input);
    }
    @GetMapping("/employees/{employeeId}/cases/{caseId}/settlements")
    public List<FinalPaySettlementService.Settlement> list(@AuthenticationPrincipal HrmUserPrincipal user,
            @PathVariable UUID employeeId,@PathVariable UUID caseId) {
        return service.list(user,employeeId,caseId);
    }
    @PostMapping("/settlements/{id}/approve")
    public FinalPaySettlementService.Settlement approve(@AuthenticationPrincipal HrmUserPrincipal user,
            @PathVariable UUID id) { return service.decide(user,id,true); }
    @PostMapping("/settlements/{id}/reject")
    public FinalPaySettlementService.Settlement reject(@AuthenticationPrincipal HrmUserPrincipal user,
            @PathVariable UUID id) { return service.decide(user,id,false); }
}
