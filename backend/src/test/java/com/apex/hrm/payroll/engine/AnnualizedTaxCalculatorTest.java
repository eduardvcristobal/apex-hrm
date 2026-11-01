package com.apex.hrm.payroll.engine;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
class AnnualizedTaxCalculatorTest {
 private static BigDecimal n(String s){return new BigDecimal(s);}
 private static List<WithholdingTaxCalculator.Bracket> brackets(){
  return List.of(new WithholdingTaxCalculator.Bracket(n("0"),n("0"),n("0")),
  new WithholdingTaxCalculator.Bracket(n("200000"),n("0"),n("0.10")));
 }
 @Test void sharedBonusCapCreatesTaxableExcess(){
  var r=AnnualizedTaxCalculator.calculate(new AnnualizedTaxCalculator.Input(
   n("240000"),n("95000"),n("5000"),n("90000"),n("3500"),brackets()));
  assertEquals(n("90000.00"),r.exemptBenefits());
  assertEquals(n("10000.00"),r.taxableBenefits());
  assertEquals(n("250000.00"),r.annualTaxableIncome());
  assertEquals(n("5000.00"),r.annualTax());
  assertEquals(n("1500.00"),r.additionalWithholdingOrRefund());
 }
 @Test void excessWithholdingIsShownAsRefund(){
  var r=AnnualizedTaxCalculator.calculate(new AnnualizedTaxCalculator.Input(
   n("100000"),n("20000"),n("5000"),n("90000"),n("500"),brackets()));
  assertEquals(n("0.00"),r.taxableBenefits());
  assertEquals(n("-500.00"),r.additionalWithholdingOrRefund());
 }
 @Test void rejectsMissingAnnualTable(){
  assertThrows(IllegalArgumentException.class,()->AnnualizedTaxCalculator.calculate(
   new AnnualizedTaxCalculator.Input(n("100"),n("0"),n("0"),n("90000"),n("0"),List.of())));
 }
}
