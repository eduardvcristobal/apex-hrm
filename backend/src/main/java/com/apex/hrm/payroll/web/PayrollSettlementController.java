package com.apex.hrm.payroll.web;

import com.apex.hrm.payroll.domain.PayrollBankSettlement;
import com.apex.hrm.payroll.service.PayrollSettlementService;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Manual bank settlement evidence workflow; it does not initiate or verify a bank transfer. */
@RestController
@RequestMapping("/api/payroll")
public class PayrollSettlementController {
    public record RecordRequest(UUID bankFileId, BigDecimal acknowledgedAmount,
            String bankReference, String evidenceReference) {}
    public record ExceptionRequest(String reason, String evidenceReference) {}

    private final PayrollSettlementService settlements;

    public PayrollSettlementController(PayrollSettlementService settlements) {
        this.settlements = settlements;
    }

    @GetMapping("/runs/{runId}/settlements")
    public PayrollSettlementService.Summary summary(@PathVariable UUID runId) {
        return settlements.summary(runId);
    }

    @PostMapping("/runs/{runId}/settlements")
    @ResponseStatus(HttpStatus.CREATED)
    public PayrollBankSettlement record(@PathVariable UUID runId, @RequestBody RecordRequest body,
            @AuthenticationPrincipal HrmUserPrincipal actor) {
        return settlements.record(runId, body.bankFileId(), body.acknowledgedAmount(),
                body.bankReference(), body.evidenceReference(), actor.id());
    }

    @PostMapping("/settlements/{id}/confirm")
    public PayrollBankSettlement confirm(@PathVariable UUID id,
            @AuthenticationPrincipal HrmUserPrincipal actor) {
        return settlements.confirm(id, actor.id());
    }

    @PostMapping("/settlements/{id}/reject")
    public PayrollBankSettlement reject(@PathVariable UUID id, @RequestBody ExceptionRequest body,
            @AuthenticationPrincipal HrmUserPrincipal actor) {
        return settlements.reject(id, body.reason(), body.evidenceReference(), actor.id());
    }

    @PostMapping("/settlements/{id}/reverse")
    public PayrollBankSettlement reverse(@PathVariable UUID id, @RequestBody ExceptionRequest body,
            @AuthenticationPrincipal HrmUserPrincipal actor) {
        return settlements.reverse(id, body.reason(), body.evidenceReference(), actor.id());
    }
}
