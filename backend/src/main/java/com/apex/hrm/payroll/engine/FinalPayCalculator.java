package com.apex.hrm.payroll.engine;
import java.math.BigDecimal;
import java.util.Objects;
/** Review-only settlement worksheet; inputs must be verified by HR and payroll. */
public final class FinalPayCalculator {
 private FinalPayCalculator() {}
 public record Input(BigDecimal unpaidBasic, BigDecimal accruedThirteenthMonth,
 BigDecimal unusedLeaveConversion, BigDecimal separationPay, BigDecimal otherEarnings,
 BigDecimal outstandingLoans, BigDecimal accountableProperty, BigDecimal withholdingTax,
 BigDecimal otherAuthorizedDeductions) {}
 public record Result(BigDecimal gross, BigDecimal deductions, BigDecimal net, boolean requiresReview) {}
 private static BigDecimal valid(BigDecimal v) {
  Objects.requireNonNull(v, "amount");
  if(v.signum()<0) throw new IllegalArgumentException("Negative settlement component");
  return Money.round(v);
 }
 public static Result preview(Input in) {
  Objects.requireNonNull(in, "input");
  BigDecimal gross=valid(in.unpaidBasic()).add(valid(in.accruedThirteenthMonth()))
   .add(valid(in.unusedLeaveConversion())).add(valid(in.separationPay())).add(valid(in.otherEarnings()));
  BigDecimal deducted=valid(in.outstandingLoans()).add(valid(in.accountableProperty()))
   .add(valid(in.withholdingTax())).add(valid(in.otherAuthorizedDeductions()));
  return new Result(gross,deducted,gross.subtract(deducted),true);
 }
}
