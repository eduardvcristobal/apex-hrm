package com.apex.hrm.personnel;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.personnel.domain.EmployeeBankAccount;
import com.apex.hrm.personnel.repo.EmployeeBankAccountRepository;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.platform.service.AuditService;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The only administrative bank-account data gateway; never returns raw numbers. */
@Service
public class BankAccountService {
    public record View(UUID id, UUID employeeId, String bankCode, String maskedAccountNo,
                       String accountName, Boolean primaryAccount) {}

    private final EmployeeBankAccountRepository accounts;
    private final EmployeeRepository employees;
    private final BankAccountCipher cipher;
    private final AuditService audit;

    public BankAccountService(EmployeeBankAccountRepository accounts, EmployeeRepository employees,
                              BankAccountCipher cipher, AuditService audit) {
        this.accounts = accounts;
        this.employees = employees;
        this.cipher = cipher;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<View> list(Pageable page) {
        return accounts.findAll(page).map(this::view);
    }

    @Transactional(readOnly = true)
    public View get(UUID id) { return view(load(id)); }

    @Transactional
    public View create(UUID employeeId, String bankCode, String number, String accountName, Boolean primary) {
        if (employeeId == null || employees.findById(employeeId).isEmpty()) {
            throw new BusinessException("Employee must belong to the current workspace");
        }
        validateCode(bankCode);
        validateNumber(number);
        EmployeeBankAccount account = new EmployeeBankAccount();
        account.setEmployeeId(employeeId);
        account.setBankCode(bankCode.trim());
        account.setAccountNoCiphertext(cipher.encrypt(number.trim(),
            com.apex.hrm.common.TenantContext.require(), employeeId));
        account.setAccountName(accountName);
        account.setPrimaryAccount(primary == null || primary);
        account = accounts.saveAndFlush(account);
        audit.log("EmployeeBankAccount", account.getId(), "CREATE", "Bank account added (number suppressed)");
        return view(account);
    }

    @Transactional
    public View update(UUID id, String bankCode, String number, String accountName, Boolean primary) {
        EmployeeBankAccount account = load(id);
        if (bankCode != null) { validateCode(bankCode); account.setBankCode(bankCode.trim()); }
        if (number != null) {
            validateNumber(number);
            account.setAccountNoCiphertext(cipher.encrypt(number.trim(), account.getTenantId(), account.getEmployeeId()));
        }
        if (accountName != null) account.setAccountName(accountName);
        if (primary != null) account.setPrimaryAccount(primary);
        account = accounts.save(account);
        audit.log("EmployeeBankAccount", id, "UPDATE", "Bank account details modified (number suppressed)");
        return view(account);
    }

    @Transactional
    public void delete(UUID id) {
        EmployeeBankAccount account = load(id);
        accounts.delete(account);
        audit.log("EmployeeBankAccount", id, "DELETE", "Bank account removed");
    }

    private EmployeeBankAccount load(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("Bank account", id));
    }

    private View view(EmployeeBankAccount a) {
        String number = cipher.decrypt(a.getAccountNoCiphertext(), a.getTenantId(), a.getEmployeeId());
        return new View(a.getId(), a.getEmployeeId(), a.getBankCode(), mask(number),
                        a.getAccountName(), a.getPrimaryAccount());
    }

    static String mask(String number) {
        if (number == null || number.isBlank()) return "****";
        String compact = number.replaceAll("[^A-Za-z0-9]", "");
        return "****" + (compact.length() > 4 ? compact.substring(compact.length() - 4) : ""); 
    }

    private static void validateCode(String code) {
        if (code == null || code.isBlank() || code.length() > 255) throw new BusinessException("Valid bank code required");
    }
    private static void validateNumber(String number) {
        if (number == null || number.isBlank() || number.length() > 255)
            throw new BusinessException("Valid account number required");
    }
}
