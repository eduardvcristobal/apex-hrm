package com.apex.hrm.payroll.engine;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Year-end annualization worksheet using externally verified annual tax brackets.
 * All totals are annual-to-date; no assumptions about official statutory rates are made.
 * Other benefits must be included for the shared bonus exemption cap.
 */
public final class AnnualizedTaxCalculator {
 private AnnualizedTaxCalculator(){}
 public record Input(BigDecimal taxableRegularIncome, BigDecimal thirteenthMonth,
  BigDecimal otherBenefits, BigDecimal sharedExemptionCap, BigDecimal withholdingToDate,
  List<WithholdingTaxCalculator.Bracket> annualBrackets) {}
 public record Result(BigDecimal exemptBenefits, BigDecimal taxableBenefits,
  BigDecimal annualTaxableIncome, BigDecimal annualTax,
  BigDecimal additionalWithholdingOrRefund) {}
 private static BigDecimal valid(BigDecimal b) {
  Objects.requireNonNull(b,"amount");
  if(b.signum()<0)throw new IllegalArgumentException("Negative annualization amount");
  return Money.round(b);
 }
 public static Result calculate(Input input) {
  Objects.requireNonNull(input,"input");
  BigDecimal regular=valid(input.taxableRegularIncome());
  BigDecimal bonuses=valid(input.thirteenthMonth()).add(valid(input.otherBenefits()));
  BigDecimal cap=valid(input.sharedExemptionCap());
  BigDecimal withheld=valid(input.withholdingToDate());
  List<WithholdingTaxCalculator.Bracket> brackets=List.copyOf(input.annualBrackets());
  if(brackets.isEmpty())throw new IllegalArgumentException("Annual tax brackets required");
  BigDecimal exempt=bonuses.min(cap);
  BigDecimal taxable=bonuses.subtract(exempt);
  BigDecimal annualIncome=regular.add(taxable);
  BigDecimal yearTax=WithholdingTaxCalculator.tax(brackets,annualIncome);
  return new Result(exempt,taxable,annualIncome,yearTax,Money.round(yearTax.subtract(withheld)));
 }
}
