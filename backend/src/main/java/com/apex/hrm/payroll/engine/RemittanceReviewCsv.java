package com.apex.hrm.payroll.engine;
import java.math.BigDecimal;
import java.util.List;
/** Internal reconciliation CSV only: NOT an SSS/PhilHealth/Pag-IBIG submission format. */
public final class RemittanceReviewCsv {
 private RemittanceReviewCsv(){}
 public record Row(String employeeNo, String employeeName, BigDecimal employeeShare,
  BigDecimal employerShare, BigDecimal employeeCompensation) {}
 private static String csv(String value) {
  String safe=value==null?"":value;
  // Spreadsheet formula injection prevention for untrusted employee names/numbers.
  if(!safe.isEmpty() && ("=+-@".indexOf(safe.charAt(0))>=0 || safe.charAt(0)=='\t'
       || safe.charAt(0)=='\r' || safe.charAt(0)=='\n')) safe="'"+safe;
  return "\"" + safe.replace("\"","\"\"") + "\"";
 }
 public static String render(String agency, List<Row> rows) {
  if(!List.of("SSS","PHILHEALTH","PAGIBIG").contains(agency))
   throw new IllegalArgumentException("Unsupported agency");
  StringBuilder out=new StringBuilder("Employee No,Employee,Employee Share,Employer Share,EC,Total\n");
  for(Row row:rows) {
   BigDecimal ee=Money.nz(row.employeeShare()),er=Money.nz(row.employerShare()),
    ec=Money.nz(row.employeeCompensation());
   out.append(csv(row.employeeNo())).append(',').append(csv(row.employeeName())).append(',')
    .append(Money.round(ee)).append(',').append(Money.round(er)).append(',')
    .append(Money.round(ec)).append(',').append(Money.round(ee.add(er).add(ec))).append('\n');
  }
  return out.toString();
 }
}
