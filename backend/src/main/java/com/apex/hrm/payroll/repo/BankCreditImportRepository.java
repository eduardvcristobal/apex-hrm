package com.apex.hrm.payroll.repo;

import com.apex.hrm.payroll.domain.BankCreditImport;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankCreditImportRepository extends JpaRepository<BankCreditImport, UUID> {
    boolean existsByBankFileId(UUID bankFileId);
    java.util.Optional<BankCreditImport> findByBankFileId(UUID bankFileId);
}
