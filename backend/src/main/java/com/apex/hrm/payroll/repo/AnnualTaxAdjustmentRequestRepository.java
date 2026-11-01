package com.apex.hrm.payroll.repo;

import com.apex.hrm.payroll.domain.AnnualTaxAdjustmentRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface AnnualTaxAdjustmentRequestRepository extends JpaRepository<AnnualTaxAdjustmentRequest, UUID> {
    Optional<AnnualTaxAdjustmentRequest> findByEmployeeIdAndTaxYear(UUID employeeId, Integer taxYear);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AnnualTaxAdjustmentRequest r where r.id = :id")
    Optional<AnnualTaxAdjustmentRequest> lockById(@Param("id") UUID id);
}
