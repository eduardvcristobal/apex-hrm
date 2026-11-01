package com.apex.hrm.payroll.service;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.payroll.domain.GlMapping;
import com.apex.hrm.payroll.domain.JournalEntry;
import com.apex.hrm.payroll.domain.JournalEntryLine;
import com.apex.hrm.payroll.domain.PayPeriod;
import com.apex.hrm.payroll.domain.PayrollEmployee;
import com.apex.hrm.payroll.domain.PayrollLine;
import com.apex.hrm.payroll.domain.PayrollRun;
import com.apex.hrm.payroll.engine.Money;
import com.apex.hrm.payroll.repo.GlMappingRepository;
import com.apex.hrm.payroll.repo.JournalEntryLineRepository;
import com.apex.hrm.payroll.repo.JournalEntryRepository;
import com.apex.hrm.payroll.repo.PayPeriodRepository;
import com.apex.hrm.payroll.repo.PayrollEmployeeRepository;
import com.apex.hrm.payroll.repo.PayrollLineRepository;
import com.apex.hrm.payroll.repo.PayrollRunRepository;
import com.apex.hrm.platform.service.AuditService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the payroll journal entry from payroll lines using the tenant's GL mapping.
 * Earnings debit expense, deductions credit payables, employer costs do both, and net pay credits salaries payable.
 */
@Service
public class JournalService {
    private record Key(UUID accountId, UUID costCenterId) {
    }

    private final PayrollRunRepository runs;
    private final PayPeriodRepository periods;
    private final PayrollLineRepository lines;
    private final PayrollEmployeeRepository payrollEmployees;
    private final GlMappingRepository mappings;
    private final JournalEntryRepository journals;
    private final JournalEntryLineRepository journalLines;
    private final AuditService audit;

    public JournalService(PayrollRunRepository runs, PayPeriodRepository periods, PayrollLineRepository lines,
                          PayrollEmployeeRepository payrollEmployees, GlMappingRepository mappings,
                          JournalEntryRepository journals, JournalEntryLineRepository journalLines, AuditService audit) {
        this.runs = runs;
        this.periods = periods;
        this.lines = lines;
        this.payrollEmployees = payrollEmployees;
        this.mappings = mappings;
        this.journals = journals;
        this.journalLines = journalLines;
        this.audit = audit;
    }

    @Transactional
    public JournalEntry generate(UUID runId) {
        PayrollRun run = runs.findById(runId).orElseThrow(() -> new NotFoundException("Payroll run", runId));
        if (!Set.of("APPROVED", "LOCKED", "PAID").contains(run.getStatus())) {
            throw new BusinessException("Journal entries are generated for approved runs only (run is " + run.getStatus() + ")");
        }
        for (JournalEntry existing : journals.findByRunId(runId)) {
            if (!"DRAFT".equals(existing.getStatus())) {
                throw new BusinessException("A " + existing.getStatus() + " journal entry already exists for this run");
            }
            journalLines.deleteByJournalEntryId(existing.getId());
            journals.delete(existing);
        }
        journals.flush();

        Map<String, GlMapping> byCode = new HashMap<>();
        for (GlMapping m : mappings.findAll()) {
            byCode.put(m.getItemCode(), m);
        }
        Map<Key, BigDecimal> debits = new LinkedHashMap<>();
        Map<Key, BigDecimal> credits = new LinkedHashMap<>();
        Set<String> missing = new TreeSet<>();

        for (PayrollLine l : lines.findByRunId(runId)) {
            GlMapping m = byCode.get(l.getItemCode());
            boolean needsDebit = !PayrollLineCategory.isDeduction(l);
            boolean needsCredit = !PayrollLineCategory.isEarning(l);
            if (m == null || (needsDebit && m.getDebitAccountId() == null) || (needsCredit && m.getCreditAccountId() == null)) {
                missing.add(l.getItemCode());
                continue;
            }
            if (needsDebit) {
                debits.merge(new Key(m.getDebitAccountId(), l.getCostCenterId()), l.getAmount(), BigDecimal::add);
            }
            if (needsCredit) {
                credits.merge(new Key(m.getCreditAccountId(), l.getCostCenterId()), l.getAmount(), BigDecimal::add);
            }
        }

        GlMapping netMapping = byCode.get("NET_PAY");
        BigDecimal net = BigDecimal.ZERO;
        for (PayrollEmployee pe : payrollEmployees.findByRunId(runId)) {
            net = net.add(pe.getNetPay());
        }
        if (netMapping == null || netMapping.getCreditAccountId() == null) {
            missing.add("NET_PAY");
        } else {
            credits.merge(new Key(netMapping.getCreditAccountId(), null), net, BigDecimal::add);
        }
        if (!missing.isEmpty()) {
            throw new BusinessException("Missing GL mapping (debit/credit account) for: " + String.join(", ", missing));
        }

        BigDecimal totalDebit = debits.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = credits.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new BusinessException("Journal does not balance: debit " + totalDebit + " vs credit " + totalCredit
                    + ". Check GL mapping and negative net pay.");
        }

        PayPeriod period = periods.findById(run.getPayPeriodId()).orElseThrow();
        JournalEntry je = new JournalEntry();
        je.setRunId(runId);
        je.setEntryDate(period.getPayDate());
        je.setDescription("Payroll " + run.getRunType() + " " + period.getStartDate() + " to " + period.getEndDate());
        je.setTotalDebit(Money.round(totalDebit));
        je.setTotalCredit(Money.round(totalCredit));
        je = journals.save(je);

        List<JournalEntryLine> out = new ArrayList<>();
        for (Map.Entry<Key, BigDecimal> e : debits.entrySet()) {
            out.add(jeLine(je, e.getKey(), Money.round(e.getValue()), BigDecimal.ZERO));
        }
        for (Map.Entry<Key, BigDecimal> e : credits.entrySet()) {
            out.add(jeLine(je, e.getKey(), BigDecimal.ZERO, Money.round(e.getValue())));
        }
        journalLines.saveAll(out);
        audit.log("JournalEntry", je.getId(), "GENERATE", "run " + runId + " total " + je.getTotalDebit());
        return je;
    }

    @Transactional
    public JournalEntry post(UUID journalEntryId) {
        JournalEntry je = journals.findById(journalEntryId).orElseThrow(() -> new NotFoundException("Journal entry", journalEntryId));
        if (!"DRAFT".equals(je.getStatus())) {
            throw new BusinessException("Journal entry is already " + je.getStatus());
        }
        je.setStatus("POSTED");
        audit.log("JournalEntry", journalEntryId, "POST", "posted");
        return journals.save(je);
    }

    private static JournalEntryLine jeLine(JournalEntry je, Key k, BigDecimal debit, BigDecimal credit) {
        JournalEntryLine l = new JournalEntryLine();
        l.setJournalEntryId(je.getId());
        l.setAccountId(k.accountId());
        l.setCostCenterId(k.costCenterId());
        l.setDebit(debit);
        l.setCredit(credit);
        return l;
    }

    /** Category helpers kept next to their only user. */
    private static final class PayrollLineCategory {
        static boolean isEarning(PayrollLine l) {
            return "EARNING".equals(l.getCategory());
        }

        static boolean isDeduction(PayrollLine l) {
            return "DEDUCTION".equals(l.getCategory());
        }
    }
}
