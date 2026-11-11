package com.apex.hrm.payroll.engine;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/** BIR withholding table lookup: tax = baseTax + rate x (taxable - lowerBound) for the matching bracket. */
public final class WithholdingTaxCalculator {
    private WithholdingTaxCalculator() {
    }

    public record Bracket(BigDecimal lowerBound, BigDecimal baseTax, BigDecimal rate, BigDecimal upperBound) {
        public Bracket(BigDecimal lowerBound, BigDecimal baseTax, BigDecimal rate) {
            this(lowerBound, baseTax, rate, null);
        }
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

    /**
     * BIR supplementary withholding is the difference between tax on regular plus supplementary
     * compensation and tax already attributable to regular compensation for the pay period.
     */
    public static BigDecimal supplementaryTax(List<Bracket> brackets, BigDecimal regularTaxable, BigDecimal supplementaryTaxable) {
        if (supplementaryTaxable.signum() <= 0) return Money.ZERO;
        BigDecimal combined = tax(brackets, regularTaxable.add(supplementaryTaxable));
        BigDecimal regular = tax(brackets, regularTaxable);
        return Money.round(combined.subtract(regular).max(BigDecimal.ZERO));
    }
}
