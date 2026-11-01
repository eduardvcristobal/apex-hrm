package com.apex.hrm.payroll.web;

import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.payroll.domain.PayrollLine;
import com.apex.hrm.payroll.repo.PayrollLineRepository;
import com.apex.hrm.payroll.repo.PayrollRunRepository;
import com.apex.hrm.payroll.engine.RemittanceReviewCsv;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Tenant-scoped payroll review export; never an agency-certified remittance upload. */
@RestController
@RequestMapping("/api/payroll")
public class RemittanceReviewController {
    private final PayrollLineRepository lines;
    private final PayrollRunRepository runs;
    private final EmployeeRepository employees;

    public RemittanceReviewController(PayrollLineRepository lines, PayrollRunRepository runs,
            EmployeeRepository employees) {
        this.lines = lines;
        this.runs = runs;
        this.employees = employees;
    }

    @GetMapping("/runs/{id}/remittance-review")
    public ResponseEntity<String> download(@PathVariable UUID id, @RequestParam String agency,
            @AuthenticationPrincipal HrmUserPrincipal user) {
        if (!Set.of("SSS", "PHILHEALTH", "PAGIBIG").contains(agency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported agency");
        }
        var run = runs.findById(id).filter(item -> user.tenantId().equals(item.getTenantId()))
                .orElseThrow(() -> new NotFoundException("Payroll run", id));
        Map<String, String[]> codes = Map.of(
                "SSS", new String[] { "SSS_EE", "SSS_ER", "SSS_EC" },
                "PHILHEALTH", new String[] { "PHIC_EE", "PHIC_ER", null },
                "PAGIBIG", new String[] { "HDMF_EE", "HDMF_ER", null });
        String[] columns = codes.get(agency);
        Map<UUID, BigDecimal[]> amounts = new LinkedHashMap<>();
        for (PayrollLine line : lines.findByRunId(run.getId())) {
            int index = line.getItemCode().equals(columns[0]) ? 0 : line.getItemCode().equals(columns[1]) ? 1
                    : columns[2] != null && line.getItemCode().equals(columns[2]) ? 2 : -1;
            if (index < 0) continue;
            BigDecimal[] row = amounts.computeIfAbsent(line.getEmployeeId(),
                    ignored -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
            row[index] = row[index].add(line.getAmount());
        }
        List<RemittanceReviewCsv.Row> rows = new ArrayList<>();
        for (var item : amounts.entrySet()) {
            var employee = employees.findById(item.getKey())
                    .filter(person -> user.tenantId().equals(person.getTenantId()))
                    .orElseThrow(() -> new NotFoundException("Employee", item.getKey()));
            BigDecimal[] amountsByCode = item.getValue();
            rows.add(new RemittanceReviewCsv.Row(employee.getEmployeeNo(),
                    employee.getFirstName() + " " + employee.getLastName(),
                    amountsByCode[0], amountsByCode[1], amountsByCode[2]));
        }
        rows.sort(Comparator.comparing(RemittanceReviewCsv.Row::employeeNo));
        String csv = RemittanceReviewCsv.render(agency, rows);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + agency.toLowerCase(Locale.ROOT) + "-review.csv\"")
                .body(csv);
    }
}
