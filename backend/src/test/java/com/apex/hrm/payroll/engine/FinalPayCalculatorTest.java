package com.apex.hrm.payroll.engine;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
class FinalPayCalculatorTest {
 private static BigDecimal n(String s){return new BigDecimal(s);}
 @Test void componentsReconcile() {
  var r=FinalPayCalculator.preview(new FinalPayCalculator.Input(n("12000"),n("4000"),n("1000"),
   n("6000"),n("500"),n("2500"),n("100"),n("1500"),n("400")));
  assertEquals(n("23500.00"),r.gross());
  assertEquals(n("4500.00"),r.deductions());
  assertEquals(n("19000.00"),r.net());
  assertTrue(r.requiresReview());
 }
 @Test void negativeNetIsVisible() {
  var r=FinalPayCalculator.preview(new FinalPayCalculator.Input(n("100"),n("0"),n("0"),
   n("0"),n("0"),n("150"),n("0"),n("0"),n("0")));
  assertEquals(n("-50.00"),r.net());
 }
 @Test void negativeComponentsRejected() {
  assertThrows(IllegalArgumentException.class,()->FinalPayCalculator.preview(
   new FinalPayCalculator.Input(n("-1"),n("0"),n("0"),n("0"),n("0"),
    n("0"),n("0"),n("0"),n("0"))));
 }
}
