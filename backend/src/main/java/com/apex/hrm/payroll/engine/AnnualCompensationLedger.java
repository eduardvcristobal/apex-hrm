package com.apex.hrm.payroll.engine;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Classifies LOCKED/PAID payroll lines into annualized compensation components.
 * Only explicitly coded 13th-month and OTHER_BENEFIT lines receive the shared exemption.
 * Employee benefit mappings must be validated before annualization is accepted.
 */
public final class AnnualCompensationLedger {
    private AnnualCompensationLedger() {}

    public record Item(String code, String category, BigDecimal amount, boolean taxable) {}
    public record Totals(BigDecimal regularTaxable, BigDecimal thirteenthMonth,
            BigDecimal otherBenefits, BigDecimal withheld) {}

    public static Totals aggregate(List<Item> items) {
        Objects.requireNonNull(items, "items");
        BigDecimal regular = BigDecimal.ZERO;
        BigDecimal thirteenth = BigDecimal.ZERO;
        BigDecimal benefits = BigDecimal.ZERO;
        BigDecimal withheld = BigDecimal.ZERO;
        for (Item item : items) {
            if (item.amount() == null || item.amount().signum() < 0)
                throw new IllegalArgumentException("Invalid payroll ledger amount");
            String code = item.code();
            if ("THIRTEENTH_MONTH".equals(code) && "EARNING".equals(item.category())) {
                thirteenth = thirteenth.add(item.amount());
            } else if ("OTHER_BENEFIT".equals(code) && "EARNING".equals(item.category())) {
                benefits = benefits.add(item.amount());
            } else if ("WTAX".equals(code) && "DEDUCTION".equals(item.category())) {
                withheld = withheld.add(item.amount());
            } else if ("EARNING".equals(item.category()) && item.taxable()) {
                regular = regular.add(item.amount());
            } else if ("DEDUCTION".equals(item.category()) &&
                    List.of("ABSENCE", "LATE_UT", "PRORATION", "SSS_EE", "PHIC_EE", "HDMF_EE").contains(code)) {
                regular = regular.subtract(item.amount());
            }
        }
        return new Totals(Money.round(regular.max(BigDecimal.ZERO)), Money.round(thirteenth),
                Money.round(benefits), Money.round(withheld));
    }
}
