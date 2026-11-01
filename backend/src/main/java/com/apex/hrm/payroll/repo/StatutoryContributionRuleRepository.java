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

public interface StatutoryContributionRuleRepository extends JpaRepository<StatutoryContributionRule, UUID> {

    @Query("select r from StatutoryContributionRule r where r.agency = :agency and r.effectiveFrom <= :date "
            + "and (r.effectiveTo is null or r.effectiveTo >= :date) order by r.effectiveFrom desc")
    List<StatutoryContributionRule> findApplicable(@Param("agency") String agency, @Param("date") LocalDate date);
}
