package com.apex.hrm.payroll.web;

import com.apex.hrm.payroll.service.FinalPaySourceReviewService;
import com.apex.hrm.payroll.service.FinalPaySourceSnapshotService;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payroll/final-pay/source-reviews")
public class FinalPaySourceReviewController {
    private final FinalPaySourceReviewService reviews;
    private final FinalPaySourceSnapshotService snapshots;
    public FinalPaySourceReviewController(FinalPaySourceReviewService reviews,
        FinalPaySourceSnapshotService snapshots){
        this.reviews=reviews;this.snapshots=snapshots;
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FinalPaySourceReviewService.Review request(
        @AuthenticationPrincipal HrmUserPrincipal user,
        @RequestBody FinalPaySourceReviewService.Request input){
        return reviews.request(user,input);
    }
    @PostMapping("/{id}/approve")
    public FinalPaySourceReviewService.Review approve(
        @AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID id){
        return reviews.decide(user,id,true);
    }
    @PostMapping("/{id}/reject")
    public FinalPaySourceReviewService.Review reject(
        @AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID id){
        return reviews.decide(user,id,false);
    }
    @GetMapping
    public List<FinalPaySourceReviewService.Review> list(
        @AuthenticationPrincipal HrmUserPrincipal user,@RequestParam UUID settlementId){
        return reviews.list(user,settlementId);
    }
    @GetMapping("/snapshots/{id}/freshness")
    public FinalPaySourceSnapshotService.Freshness freshness(
        @AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID id){
        return snapshots.verifyCurrent(user,id);
    }
}
