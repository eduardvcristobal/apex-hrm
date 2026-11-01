package com.apex.hrm.payroll.web;

import com.apex.hrm.payroll.domain.BankCreditResult;
import com.apex.hrm.payroll.service.BankCreditResultService;
import com.apex.hrm.payroll.service.BankFileService;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Operator-controlled import of a verified bank response and recovery export.
 * The app does not verify bank signatures or initiate transfers.
 */
@RestController
@RequestMapping("/api/payroll/bank-results")
public class BankCreditResultController {
    public record ImportRequest(String csv, String evidenceReference) {}

    private final BankCreditResultService results;
    private final BankFileService bankFiles;

    public BankCreditResultController(BankCreditResultService results, BankFileService bankFiles) {
        this.results = results;
        this.bankFiles = bankFiles;
    }

    @PostMapping("/{bankFileId}/import")
    @ResponseStatus(HttpStatus.CREATED)
    public BankCreditResultService.ImportSummary importBankResult(
            @PathVariable UUID bankFileId, @RequestBody ImportRequest body,
            @AuthenticationPrincipal HrmUserPrincipal actor) {
        return results.importResults(bankFileId, body.csv(), body.evidenceReference(), actor.id());
    }

    @GetMapping("/{bankFileId}")
    public List<BankCreditResult> employeeOutcomes(@PathVariable UUID bankFileId) {
        return results.results(bankFileId);
    }

    @PostMapping("/{bankFileId}/recover")
    @ResponseStatus(HttpStatus.CREATED)
    public BankFileService.Result recover(@PathVariable UUID bankFileId) {
        return bankFiles.recoverRejected(bankFileId);
    }
}
