package com.apex.hrm.payroll.engine;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/** BIR withholding table lookup: tax = baseTax + rate x (taxable - lowerBound) for the matching bracket. */
public final class WithholdingTaxCalculator {
    private WithholdingTaxCalculator() {
    }

    public record Bracket(BigDecimal lowerBound, BigDecimal baseTax, BigDecimal rate) {
    }

    public static BigDecimal tax(List<Bracket> brackets, BigDecimal taxableIncome) {
        if (taxableIncome.signum() <= 0 || brackets.isEmpty()) {
            return Money.ZERO;
        }
        Bracket match = brackets.stream()
                .filter(b -> taxableIncome.compareTo(b.lowerBound()) >= 0)
                .max(Comparator.comparing(Bracket::lowerBound))
                .orElse(null);
        if (match == null) {
            return Money.ZERO;
        }
        return Money.round(match.baseTax().add(taxableIncome.subtract(match.lowerBound()).multiply(match.rate())));
    }
}
