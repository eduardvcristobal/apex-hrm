package com.apex.hrm.personnel.service;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.personnel.domain.Compensation;
import com.apex.hrm.personnel.repo.CompensationRepository;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Effective-dated salary history: a change closes the open record the day before and opens a new one. */
@Service
public class CompensationService {
    private final CompensationRepository compensations;
    private final EmployeeRepository employees;

    public CompensationService(CompensationRepository compensations, EmployeeRepository employees) {
        this.compensations = compensations;
        this.employees = employees;
    }

    @Transactional
    public Compensation change(UUID employeeId, LocalDate effectiveFrom, String payBasis, BigDecimal baseRate, String reason) {
        employees.findById(employeeId).orElseThrow(() -> new NotFoundException("Employee", employeeId));
        if (effectiveFrom == null) {
            throw new BusinessException("effectiveFrom is required");
        }
        if (!"MONTHLY".equals(payBasis) && !"DAILY".equals(payBasis)) {
            throw new BusinessException("payBasis must be MONTHLY or DAILY");
        }
        if (baseRate == null || baseRate.signum() <= 0) {
            throw new BusinessException("baseRate must be greater than zero");
        }
        compensations.findFirstByEmployeeIdAndEffectiveToIsNull(employeeId).ifPresent(open -> {
            if (!effectiveFrom.isAfter(open.getEffectiveFrom())) {
                throw new BusinessException("effectiveFrom must be after the current record's start date " + open.getEffectiveFrom());
            }
            open.setEffectiveTo(effectiveFrom.minusDays(1));
            compensations.save(open);
        });
        Compensation c = new Compensation();
        c.setEmployeeId(employeeId);
        c.setEffectiveFrom(effectiveFrom);
        c.setPayBasis(payBasis);
        c.setBaseRate(baseRate);
        c.setReason(reason);
        return compensations.save(c);
    }

    @Transactional(readOnly = true)
    public List<Compensation> history(UUID employeeId) {
        return compensations.findByEmployeeIdOrderByEffectiveFromDesc(employeeId);
    }
}
