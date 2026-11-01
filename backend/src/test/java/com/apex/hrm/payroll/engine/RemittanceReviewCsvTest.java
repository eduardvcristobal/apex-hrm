package com.apex.hrm.payroll.engine;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
class RemittanceReviewCsvTest {
 private static BigDecimal n(String s){return new BigDecimal(s);}
 @Test void correctlyTotalsShares() {
  String csv=RemittanceReviewCsv.render("SSS",List.of(new RemittanceReviewCsv.Row(
   "EMP01","Sample Employee",n("1750"),n("3500"),n("30"))));
  assertTrue(csv.contains("1750.00,3500.00,30.00,5280.00"));
 }
 @Test void escapesCsvAndSpreadsheetFormulaInjection(){
  String csv=RemittanceReviewCsv.render("PAGIBIG",List.of(new RemittanceReviewCsv.Row(
   "=2+2","Person, \"Test\"",n("100"),n("100"),n("0"))));
  assertTrue(csv.contains("\"'=2+2\""));
  assertTrue(csv.contains("\"Person, \"\"Test\"\"\""));
 }
 @Test void rejectsUnknownAgency(){
  assertThrows(IllegalArgumentException.class,()->RemittanceReviewCsv.render("BIR",List.of()));
 }
}
