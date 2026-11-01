package com.apex.hrm.payroll.web;
import com.apex.hrm.payroll.engine.FinalPayCalculator;
import org.springframework.web.bind.annotation.*;
/** Calculation preview only; this endpoint does not finalize or disburse pay. */
@RestController
@RequestMapping("/api/payroll/final-pay")
public class FinalPayPreviewController {
 @PostMapping("/preview")
 public FinalPayCalculator.Result preview(@RequestBody FinalPayCalculator.Input input) {
  return FinalPayCalculator.preview(input);
 }
}
