package com.apex.hrm.personnel;

import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.common.TenantContext;
import com.apex.hrm.personnel.domain.Employee;
import com.apex.hrm.personnel.domain.EmployeeBankAccount;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.personnel.repo.EmployeeBankAccountRepository;
import com.apex.hrm.platform.service.AuditService;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class BankAccountServiceTest {
    private final UUID tenant = UUID.randomUUID();
    private final UUID employee = UUID.randomUUID();
    @Mock EmployeeBankAccountRepository accounts;
    @Mock EmployeeRepository employees;
    @Mock AuditService audit;
    BankAccountCipher cipher;
    BankAccountService service;

    @BeforeEach
    void setUp() {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) 7);
        cipher = new BankAccountCipher(Base64.getEncoder().encodeToString(bytes), "k1", "", "");
        service = new BankAccountService(accounts, employees, cipher, audit);
        TenantContext.set(tenant);
    }
    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void createEncryptsPersistedValueAndReturnsMaskedOnly() {
        when(employees.findById(employee)).thenReturn(Optional.of(new Employee()));
        when(accounts.saveAndFlush(any())).thenAnswer(call -> {
            EmployeeBankAccount account = call.getArgument(0);
            account.setId(UUID.randomUUID());
            account.setTenantId(tenant);
            return account;
        });
        BankAccountService.View dto = service.create(employee, "BDO", "001234567890", "Employee", true);
        assertEquals("****7890", dto.maskedAccountNo());
        assertFalse(dto.toString().contains("001234567890"));
        var captured = org.mockito.ArgumentCaptor.forClass(EmployeeBankAccount.class);
        verify(accounts).saveAndFlush(captured.capture());
        assertFalse(captured.getValue().getAccountNoCiphertext().contains("001234567890"));
        assertEquals("001234567890", cipher.decrypt(captured.getValue().getAccountNoCiphertext(), tenant, employee));
    }

    @Test
    void rejectsForeignTenantEvenWhenRepositoryReturnsIt() {
        EmployeeBankAccount alien = new EmployeeBankAccount();
        alien.setId(UUID.randomUUID());
        alien.setTenantId(UUID.randomUUID());
        alien.setEmployeeId(employee);
        alien.setAccountNoCiphertext(cipher.encrypt("0012345", alien.getTenantId(), employee));
        when(accounts.findById(alien.getId())).thenReturn(Optional.of(alien));
        assertThrows(NotFoundException.class, () -> service.get(alien.getId()));
        assertThrows(NotFoundException.class, () -> service.update(alien.getId(), "BDO", null, null, null));
        assertThrows(NotFoundException.class, () -> service.delete(alien.getId()));
        verify(accounts, never()).save(any());
        verify(accounts, never()).delete(any());
    }

    @Test
    void cannotCreateAccountForUnknownEmployee() {
        when(employees.findById(employee)).thenReturn(Optional.empty());
        assertThrows(com.apex.hrm.common.BusinessException.class, () ->
            service.create(employee, "BDO", "123456789", "Employee", true));
        verify(accounts, never()).saveAndFlush(any());
    }
}
