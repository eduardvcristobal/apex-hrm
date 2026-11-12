package com.apex.hrm.payroll.repo;

import com.apex.hrm.payroll.domain.EmployeePeraTaxCredit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeePeraTaxCreditRepository extends JpaRepository<EmployeePeraTaxCredit, UUID> {
    Optional<EmployeePeraTaxCredit> findByEmployeeIdAndTaxYear(UUID employeeId, Integer taxYear);
}
