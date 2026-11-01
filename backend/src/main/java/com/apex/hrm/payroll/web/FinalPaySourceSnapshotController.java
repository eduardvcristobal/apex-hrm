package com.apex.hrm.payroll.web;

import com.apex.hrm.payroll.service.FinalPaySourceSnapshotService;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Read-only evidence snapshots and append-only capture; no payment certification. */
@RestController
@RequestMapping("/api/v1/payroll/final-pay/source-snapshots")
public class FinalPaySourceSnapshotController {
    private final FinalPaySourceSnapshotService service;
    public FinalPaySourceSnapshotController(FinalPaySourceSnapshotService service){this.service=service;}
    @PostMapping("/settlements/{settlementId}")
    @ResponseStatus(HttpStatus.CREATED)
    public FinalPaySourceSnapshotService.Snapshot capture(
        @AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID settlementId) {
        return service.capture(user,settlementId);
    }
    @GetMapping("/settlements/{settlementId}")
    public List<FinalPaySourceSnapshotService.Snapshot> list(
        @AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID settlementId) {
        return service.list(user,settlementId);
    }
}
