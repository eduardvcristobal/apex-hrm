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
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayPeriodRepository extends JpaRepository<PayPeriod, UUID> {

    /** Serialize run creation per period to avoid duplicate regular or 13th-month runs. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PayPeriod p where p.id = :id")
    Optional<PayPeriod> findLockedById(@Param("id") UUID id);

    List<PayPeriod> findByPayGroupId(UUID payGroupId);
}
