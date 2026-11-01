package com.apex.hrm.payroll.engine;
import java.math.BigDecimal;
/** Pag-IBIG contribution using effective-dated regular and low-income rates. */
public final class PagIbigCalculator {
 private PagIbigCalculator(){}
 public record Rule(BigDecimal employeeRate, BigDecimal employerRate, BigDecimal maxBase,
   BigDecimal lowIncomeThreshold, BigDecimal lowIncomeEmployeeRate) {
  public Rule(BigDecimal employeeRate, BigDecimal employerRate, BigDecimal maxBase) {
   this(employeeRate,employerRate,maxBase,null,null);
  }
 }
 public record Result(BigDecimal base, BigDecimal employee, BigDecimal employer) {}
 public static Result monthly(Rule r, BigDecimal monthlyCompensation) {
  BigDecimal base=monthlyCompensation.min(r.maxBase());
  BigDecimal rate=r.lowIncomeThreshold()!=null && r.lowIncomeEmployeeRate()!=null
    && monthlyCompensation.compareTo(r.lowIncomeThreshold())<=0
      ? r.lowIncomeEmployeeRate():r.employeeRate();
  return new Result(base,Money.round(base.multiply(rate)),
   Money.round(base.multiply(r.employerRate())));
 }
}
