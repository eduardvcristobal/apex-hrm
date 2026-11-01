package com.apex.hrm.payroll.engine;

import java.math.BigDecimal;
import java.util.List;

/** Internal reconciliation export only; this is not an agency submission format. */
public final class RemittanceReviewCsv {
    private RemittanceReviewCsv() {}

    public record Row(String employeeNo, String employeeName, BigDecimal employeeShare,
            BigDecimal employerShare, BigDecimal ecShare) {}

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && ("=+-@".indexOf(safe.charAt(0)) >= 0 ||
                safe.charAt(0) == '\t' || safe.charAt(0) == '\r' || safe.charAt(0) == '\n')) {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    public static String render(String agency, List<Row> rows) {
        if (!List.of("SSS", "PHILHEALTH", "PAGIBIG").contains(agency)) {
            throw new IllegalArgumentException("Unsupported agency");
        }
        StringBuilder out = new StringBuilder("Employee No,Employee,Employee Share,Employer Share,EC,Total\n");
        for (Row row : rows) {
            BigDecimal employee = Money.nz(row.employeeShare());
            BigDecimal employer = Money.nz(row.employerShare());
            BigDecimal ec = Money.nz(row.ecShare());
            out.append(csv(row.employeeNo())).append(',').append(csv(row.employeeName())).append(',')
                    .append(Money.round(employee)).append(',').append(Money.round(employer)).append(',')
                    .append(Money.round(ec)).append(',').append(Money.round(employee.add(employer).add(ec))).append('\n');
        }
        return out.toString();
    }
}
