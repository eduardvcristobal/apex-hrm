package com.apex.hrm.timekeeping.repo;

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
import org.springframework.data.repository.query.Param;

public interface LeaveLedgerRepository extends JpaRepository<LeaveLedger, UUID> {

    @Query("select coalesce(sum(l.days), 0) from LeaveLedger l where l.employeeId = :e and l.leaveTypeId = :t")
    BigDecimal balance(@Param("e") UUID employeeId, @Param("t") UUID leaveTypeId);
}
