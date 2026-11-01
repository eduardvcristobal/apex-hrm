package com.apex.hrm.payroll.repo;

import com.apex.hrm.payroll.domain.PayrollBankSettlement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayrollBankSettlementRepository extends JpaRepository<PayrollBankSettlement, UUID> {
    List<PayrollBankSettlement> findByRunId(UUID runId);
    boolean existsByBankFileId(UUID bankFileId);

    @Override
    @Query("select s from PayrollBankSettlement s where s.id = :id and s.tenantId = ?#{T(com.apex.hrm.common.TenantContext).get()}")
    Optional<PayrollBankSettlement> findById(@Param("id") UUID id);
}
