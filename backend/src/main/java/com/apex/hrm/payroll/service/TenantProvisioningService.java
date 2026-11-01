package com.apex.hrm.payroll.service;

import com.apex.hrm.payroll.domain.GlAccount;
import com.apex.hrm.payroll.domain.GlMapping;
import com.apex.hrm.payroll.domain.PayItemType;
import com.apex.hrm.payroll.repo.GlAccountRepository;
import com.apex.hrm.payroll.repo.GlMappingRepository;
import com.apex.hrm.payroll.repo.PayItemTypeRepository;
import com.apex.hrm.timekeeping.domain.LeaveType;
import com.apex.hrm.timekeeping.repo.LeaveTypeRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Seeds a new tenant (the current TenantContext) with pay items, leave types and a starter chart of accounts. */
@Service
public class TenantProvisioningService {
    private final PayItemTypeRepository payItems;
    private final LeaveTypeRepository leaveTypes;
    private final GlAccountRepository accounts;
    private final GlMappingRepository mappings;

    public TenantProvisioningService(PayItemTypeRepository payItems, LeaveTypeRepository leaveTypes,
                                     GlAccountRepository accounts, GlMappingRepository mappings) {
        this.payItems = payItems;
        this.leaveTypes = leaveTypes;
        this.accounts = accounts;
        this.mappings = mappings;
    }

    @Transactional
    public void provision() {
        item("BASIC", "Basic pay", "EARNING", true);
        item("HOLIDAY_PAY", "Holiday pay", "EARNING", true);
        item("LEAVE_PAY", "Paid leave", "EARNING", true);
        item("PREMIUM", "Holiday / rest day premium", "EARNING", true);
        item("OT", "Overtime pay", "EARNING", true);
        item("ND", "Night differential", "EARNING", true);
        item("ALLOWANCE", "Taxable allowance", "EARNING", true);
        item("DE_MINIMIS", "De minimis benefit", "EARNING", false);
        item("THIRTEENTH_MONTH", "13th month pay", "EARNING", false);
        item("ABSENCE", "Absences", "DEDUCTION", true);
        item("LATE_UT", "Late / undertime", "DEDUCTION", true);
        item("SSS_EE", "SSS contribution", "DEDUCTION", false);
        item("PHIC_EE", "PhilHealth contribution", "DEDUCTION", false);
        item("HDMF_EE", "Pag-IBIG contribution", "DEDUCTION", false);
        item("WTAX", "Withholding tax", "DEDUCTION", false);
        item("LOAN", "Loan amortization", "DEDUCTION", false);
        item("SSS_ER", "SSS employer share", "EMPLOYER_COST", false);
        item("SSS_EC", "SSS employees' compensation", "EMPLOYER_COST", false);
        item("PHIC_ER", "PhilHealth employer share", "EMPLOYER_COST", false);
        item("HDMF_ER", "Pag-IBIG employer share", "EMPLOYER_COST", false);
        item("LEAVE_CONVERSION", "Conversion of unused leave", "EARNING", true);
        item("TAX_REFUND", "Refund of excess tax withheld", "EARNING", false);

        // Statutory entitlements are examples to verify against current law and company policy.
        leave("SIL", "Service incentive leave", true, "5");
        leave("VL", "Vacation leave", true, null);
        leave("SL", "Sick leave", true, null);
        leave("ML", "Maternity leave", true, "105");
        leave("PL", "Paternity leave", true, "7");
        leave("SPL", "Solo parent leave", true, "7");
        leave("UL", "Unpaid leave", false, null);

        Map<String, GlAccount> gl = new HashMap<>();
        for (String[] a : new String[][]{
                {"1150", "Employee Loans Receivable", "ASSET"}, {"2100", "SSS Payable", "LIABILITY"},
                {"2110", "PhilHealth Payable", "LIABILITY"}, {"2120", "Pag-IBIG Payable", "LIABILITY"},
                {"2130", "Withholding Tax Payable", "LIABILITY"}, {"2140", "Salaries Payable", "LIABILITY"},
                {"5100", "Salaries and Wages", "EXPENSE"}, {"5110", "Overtime and Premium Pay", "EXPENSE"},
                {"5120", "Allowances and Benefits", "EXPENSE"}, {"5130", "Employer Contributions", "EXPENSE"}}) {
            GlAccount acc = new GlAccount();
            acc.setCode(a[0]);
            acc.setName(a[1]);
            acc.setAccountType(a[2]);
            gl.put(a[0], accounts.save(acc));
        }
        map(gl, "BASIC", "5100", null);
        map(gl, "HOLIDAY_PAY", "5100", null);
        map(gl, "LEAVE_PAY", "5100", null);
        map(gl, "THIRTEENTH_MONTH", "5100", null);
        map(gl, "PREMIUM", "5110", null);
        map(gl, "OT", "5110", null);
        map(gl, "ND", "5110", null);
        map(gl, "ALLOWANCE", "5120", null);
        map(gl, "DE_MINIMIS", "5120", null);
        map(gl, "ABSENCE", null, "5100");
        map(gl, "LATE_UT", null, "5100");
        map(gl, "SSS_EE", null, "2100");
        map(gl, "PHIC_EE", null, "2110");
        map(gl, "HDMF_EE", null, "2120");
        map(gl, "WTAX", null, "2130");
        map(gl, "LOAN", null, "1150");
        map(gl, "SSS_ER", "5130", "2100");
        map(gl, "SSS_EC", "5130", "2100");
        map(gl, "PHIC_ER", "5130", "2110");
        map(gl, "HDMF_ER", "5130", "2120");
        map(gl, "LEAVE_CONVERSION", "5100", null);
        map(gl, "TAX_REFUND", "2130", null);
        map(gl, "NET_PAY", null, "2140");
    }

    private void item(String code, String name, String category, boolean taxable) {
        PayItemType p = new PayItemType();
        p.setCode(code);
        p.setName(name);
        p.setCategory(category);
        p.setTaxable(taxable);
        p.setSystemItem(true);
        payItems.save(p);
    }

    private void leave(String code, String name, boolean paid, String entitlement) {
        LeaveType t = new LeaveType();
        t.setCode(code);
        t.setName(name);
        t.setPaid(paid);
        t.setAnnualEntitlement(entitlement == null ? null : new BigDecimal(entitlement));
        leaveTypes.save(t);
    }

    private void map(Map<String, GlAccount> gl, String itemCode, String debit, String credit) {
        GlMapping m = new GlMapping();
        m.setItemCode(itemCode);
        m.setDebitAccountId(debit == null ? null : gl.get(debit).getId());
        m.setCreditAccountId(credit == null ? null : gl.get(credit).getId());
        mappings.save(m);
    }
}
