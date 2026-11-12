package com.apex.hrm.personnel.service;

import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.personnel.PrivateDataCipher;
import com.apex.hrm.personnel.domain.Employee;
import com.apex.hrm.personnel.domain.EmployeeBankAccount;
import com.apex.hrm.personnel.repo.EmployeeBankAccountRepository;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Service
public class EmployeeBankAccountService {
    public record Account(UUID id, @NotNull UUID employeeId, @NotBlank String bankCode, @NotBlank String accountNo,
                          String accountName, Boolean primaryAccount) {}

    private final EmployeeBankAccountRepository accounts;
    private final EmployeeRepository employees;
    private final PrivateDataCipher cipher;

    public EmployeeBankAccountService(EmployeeBankAccountRepository accounts, EmployeeRepository employees,
                                      PrivateDataCipher cipher) {
        this.accounts = accounts;
        this.employees = employees;
        this.cipher = cipher;
    }

    @Transactional(readOnly = true)
    public Page<Account> list(Pageable pageable) {
        return accounts.findAll(pageable).map(this::view);
    }

    @Transactional
    public Account get(UUID id) { return view(require(id)); }

    @Transactional
    public Account create(Account input) {
        Employee employee = employees.findById(input.employeeId()).orElseThrow(() -> new NotFoundException("Employee", input.employeeId()));
        EmployeeBankAccount entity = new EmployeeBankAccount();
        entity.setEmployeeId(employee.getId());
        entity.setBankCode(input.bankCode());
        entity.setPrimaryAccount(input.primaryAccount() == null || input.primaryAccount());
        encrypt(entity, input.accountNo(), input.accountName());
        return view(accounts.save(entity));
    }

    @Transactional
    public Account update(UUID id, Account input) {
        EmployeeBankAccount entity = require(id);
        if (input.employeeId() != null && !input.employeeId().equals(entity.getEmployeeId()))
            throw new IllegalArgumentException("Bank account employee cannot be changed");
        if (input.bankCode() != null) entity.setBankCode(input.bankCode());
        if (input.primaryAccount() != null) entity.setPrimaryAccount(input.primaryAccount());
        if (input.accountNo() != null || input.accountName() != null) {
            String oldNo = decode(entity.getAccountNoCiphertext(), entity.getAccountNo(), context(entity, "no"));
            String oldName = decode(entity.getAccountNameCiphertext(), entity.getAccountName(), context(entity, "name"));
            encrypt(entity, input.accountNo() == null ? oldNo : input.accountNo(), input.accountName() == null ? oldName : input.accountName());
        }
        return view(accounts.save(entity));
    }

    @Transactional
    public void delete(UUID id) { accounts.delete(require(id)); }

    @Transactional
    public int encryptLegacy() {
        List<EmployeeBankAccount> rows = accounts.findAll();
        int migrated = 0;
        for (EmployeeBankAccount entity : rows) {
            if (entity.getAccountNo() == null && entity.getAccountName() == null) continue;
            encrypt(entity, entity.getAccountNo(), entity.getAccountName());
            accounts.save(entity);
            migrated++;
        }
        return migrated;
    }

    @Transactional
    public Account decryptForPayroll(EmployeeBankAccount entity) {
        return view(entity);
    }

    private Account view(EmployeeBankAccount entity) {
        String no = decode(entity.getAccountNoCiphertext(), entity.getAccountNo(), context(entity, "no"));
        String name = decode(entity.getAccountNameCiphertext(), entity.getAccountName(), context(entity, "name"));
        return new Account(entity.getId(), entity.getEmployeeId(), entity.getBankCode(), no, name, entity.getPrimaryAccount());
    }

    private void encrypt(EmployeeBankAccount entity, String no, String name) {
        entity.setAccountNoCiphertext(cipher.encrypt(no, context(entity, "no")));
        entity.setAccountNameCiphertext(cipher.encrypt(name, context(entity, "name")));
        entity.setAccountNo(null);
        entity.setAccountName(null);
    }

    private String decode(String encrypted, String legacy, String context) {
        return encrypted == null ? legacy : cipher.decrypt(encrypted, context);
    }

    private static String context(EmployeeBankAccount entity, String field) {
        return "employee-bank-account:" + entity.getEmployeeId() + ":" + field;
    }

    private EmployeeBankAccount require(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("Employee bank account", id));
    }
}
