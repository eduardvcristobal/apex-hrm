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
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayPeriodRepository extends JpaRepository<PayPeriod, UUID> {

    /** Serialize payroll run creation and period state changes for this tenant. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PayPeriod p where p.id = :id and p.tenantId = ?#{T(com.apex.hrm.common.TenantContext).get()}")
    Optional<PayPeriod> lockForPayroll(@Param("id") UUID id);

    List<PayPeriod> findByPayGroupId(UUID payGroupId);
}
