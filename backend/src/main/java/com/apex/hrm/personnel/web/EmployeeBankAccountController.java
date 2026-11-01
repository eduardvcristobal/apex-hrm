package com.apex.hrm.personnel.web;

import com.apex.hrm.personnel.BankAccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Mask-only administrative API; never serializes the entity or its ciphertext. */
@RestController
@RequestMapping("/api/employee-bank-accounts")
public class EmployeeBankAccountController {
    public record Create(@NotNull UUID employeeId, @NotBlank String bankCode,
                         @NotBlank String accountNo, String accountName, Boolean primaryAccount) {}
    public record Update(String bankCode, String accountNo, String accountName, Boolean primaryAccount) {}

    private final BankAccountService accounts;
    public EmployeeBankAccountController(BankAccountService accounts) { this.accounts = accounts; }

    @GetMapping
    public Page<BankAccountService.View> list(Pageable page) { return accounts.list(page); }

    @GetMapping("/{id}")
    public BankAccountService.View get(@PathVariable UUID id) { return accounts.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BankAccountService.View create(@Valid @RequestBody Create request) {
        return accounts.create(request.employeeId(),request.bankCode(),request.accountNo(),
            request.accountName(),request.primaryAccount());
    }

    @PutMapping("/{id}")
    public BankAccountService.View update(@PathVariable UUID id,@Valid @RequestBody Update request) {
        return accounts.update(id,request.bankCode(),request.accountNo(),request.accountName(),request.primaryAccount());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { accounts.delete(id); }
}
