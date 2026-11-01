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
import org.springframework.data.repository.query.Param;

public interface WageOrderRepository extends JpaRepository<WageOrder, UUID> {

    @Query("select w from WageOrder w where w.regionCode = :region and w.sector = :sector "
            + "and w.effectiveFrom <= :date and (w.effectiveTo is null or w.effectiveTo >= :date) "
            + "order by w.effectiveFrom desc")
    List<WageOrder> findApplicable(@Param("region") String regionCode, @Param("sector") String sector,
                                   @Param("date") LocalDate date);
}
