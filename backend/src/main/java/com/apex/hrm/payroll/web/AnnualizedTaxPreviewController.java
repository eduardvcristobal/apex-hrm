package com.apex.hrm.payroll.web;
import com.apex.hrm.payroll.engine.AnnualizedTaxCalculator;
import org.springframework.web.bind.annotation.*;
/** Payroll-officer MFA-protected preview; no adjustment or filing is created. */
@RestController
@RequestMapping("/api/payroll/tax")
public class AnnualizedTaxPreviewController {
 @PostMapping("/annualization-preview")
 public AnnualizedTaxCalculator.Result preview(@RequestBody AnnualizedTaxCalculator.Input input) {
  return AnnualizedTaxCalculator.calculate(input);
 }
}
