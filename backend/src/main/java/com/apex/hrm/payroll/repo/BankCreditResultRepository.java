package com.apex.hrm.payroll.repo;

import com.apex.hrm.payroll.domain.BankCreditResult;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankCreditResultRepository extends JpaRepository<BankCreditResult, UUID> {
    List<BankCreditResult> findByBankFileId(UUID bankFileId);
    List<BankCreditResult> findByEmployeeIdAndCreditStatus(UUID employeeId, String creditStatus);
}
