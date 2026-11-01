package com.apex.hrm.payroll.repo;

import com.apex.hrm.payroll.domain.AnnualTaxAdjustmentRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnualTaxAdjustmentRequestRepository extends JpaRepository<AnnualTaxAdjustmentRequest, UUID> {
    Optional<AnnualTaxAdjustmentRequest> findByEmployeeIdAndTaxYear(UUID employeeId, Integer taxYear);
}
