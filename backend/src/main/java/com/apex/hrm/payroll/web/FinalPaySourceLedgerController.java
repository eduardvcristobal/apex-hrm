package com.apex.hrm.payroll.web;

import com.apex.hrm.payroll.service.FinalPaySourceLedgerService;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payroll/final-pay/source-ledger")
public class FinalPaySourceLedgerController {
    private final FinalPaySourceLedgerService source;
    public FinalPaySourceLedgerController(FinalPaySourceLedgerService source){this.source=source;}
    @GetMapping("/settlements/{id}")
    public FinalPaySourceLedgerService.SourceReview review(
            @AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID id) {
        return source.review(user,id);
    }
}
