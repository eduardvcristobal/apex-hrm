package com.apex.hrm.payroll.repo;

import com.apex.hrm.org.domain.*;
import com.apex.hrm.payroll.domain.*;
import com.apex.hrm.personnel.domain.*;
import com.apex.hrm.platform.domain.*;
import com.apex.hrm.timekeeping.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

public interface PayrollRunRepository extends JpaRepository<PayrollRun, UUID> {

    /** Explicit tenant guard: Hibernate identifier lookups must not expose other workspace runs. */
    @Override
    @Query("select r from PayrollRun r where r.id = :id and r.tenantId = ?#{T(com.apex.hrm.common.TenantContext).get()}")
    Optional<PayrollRun> findById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PayrollRun r where r.id = :id and r.tenantId = ?#{T(com.apex.hrm.common.TenantContext).get()}")
    Optional<PayrollRun> lockForSettlement(@Param("id") UUID id);

    List<PayrollRun> findByPayPeriodIdAndRunType(UUID payPeriodId, String runType);
}
