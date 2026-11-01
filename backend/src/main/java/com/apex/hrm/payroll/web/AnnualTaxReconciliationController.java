package com.apex.hrm.payroll.web;

import com.apex.hrm.payroll.service.AnnualTaxReconciliationService;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Read-only year-to-date annual tax reconciliation from finalized payroll lines. */
@RestController
@RequestMapping("/api/payroll/tax")
public class AnnualTaxReconciliationController {
    private final AnnualTaxReconciliationService service;
    public AnnualTaxReconciliationController(AnnualTaxReconciliationService service) {
        this.service = service;
    }
    @GetMapping("/employees/{employeeId}/annualization")
    public AnnualTaxReconciliationService.Reconciliation preview(@PathVariable UUID employeeId,
            @RequestParam int year) {
        return service.preview(employeeId, year);
    }
}
