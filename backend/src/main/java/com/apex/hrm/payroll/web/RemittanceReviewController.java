package com.apex.hrm.payroll.web;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.payroll.engine.RemittanceReviewCsv;
import com.apex.hrm.payroll.domain.PayrollLine;
import com.apex.hrm.payroll.repo.PayrollLineRepository;
import com.apex.hrm.payroll.repo.PayrollRunRepository;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
/** Authenticated payroll review register, never an agency-certified remittance upload. */
@RestController
@RequestMapping("/api/payroll")
public class RemittanceReviewController {
 private final PayrollLineRepository lines;
 private final PayrollRunRepository runs;
 private final EmployeeRepository employees;
 public RemittanceReviewController(PayrollLineRepository lines,PayrollRunRepository runs,
   EmployeeRepository employees){this.lines=lines;this.runs=runs;this.employees=employees;}
 @GetMapping("/runs/{id}/remittance-review")
 public ResponseEntity<String> download(@PathVariable UUID id,@RequestParam String agency) {
  if(!Set.of("SSS","PHILHEALTH","PAGIBIG").contains(agency))
   return ResponseEntity.badRequest().body("Unsupported agency");
  if(!runs.existsById(id))throw new NotFoundException("Payroll run",id);
  Map<String,String[]> codes=Map.of(
   "SSS",new String[]{"SSS_EE","SSS_ER","SSS_EC"},
   "PHILHEALTH",new String[]{"PHIC_EE","PHIC_ER",null},
   "PAGIBIG",new String[]{"HDMF_EE","HDMF_ER",null});
  String[] cols=codes.get(agency);
  Map<UUID,BigDecimal[]> amounts=new LinkedHashMap<>();
  for(PayrollLine l:lines.findByRunId(id)){
   int index= l.getItemCode().equals(cols[0])?0:
    l.getItemCode().equals(cols[1])?1:
    cols[2]!=null && l.getItemCode().equals(cols[2])?2:-1;
   if(index<0)continue;
   BigDecimal[] row=amounts.computeIfAbsent(l.getEmployeeId(),
    ignored->new BigDecimal[]{BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO});
   row[index]=row[index].add(l.getAmount());
  }
  List<RemittanceReviewCsv.Row> rows=new ArrayList<>();
  for(var item:amounts.entrySet()){
   var emp=employees.findById(item.getKey()).orElseThrow();
   var a=item.getValue();
   rows.add(new RemittanceReviewCsv.Row(emp.getEmployeeNo(),
    emp.getFirstName()+" "+emp.getLastName(),a[0],a[1],a[2]));
  }
  rows.sort(Comparator.comparing(RemittanceReviewCsv.Row::employeeNo));
  String csv=RemittanceReviewCsv.render(agency,rows);
  return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
   .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+agency.toLowerCase(Locale.ROOT)+"-review.csv\"")
   .body(csv);
 }
}
