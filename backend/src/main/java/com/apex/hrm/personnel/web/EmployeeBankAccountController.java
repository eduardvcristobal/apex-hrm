package com.apex.hrm.personnel.web;

import com.apex.hrm.personnel.service.EmployeeBankAccountService;
import com.apex.hrm.personnel.service.EmployeeBankAccountService.Account;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/employee-bank-accounts")
public class EmployeeBankAccountController {
    private final EmployeeBankAccountService service;

    public EmployeeBankAccountController(EmployeeBankAccountService service) { this.service = service; }

    @GetMapping public Page<Account> list(Pageable pageable) { return service.list(pageable); }
    @GetMapping("/{id}") public Account get(@PathVariable UUID id) { return service.get(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Account create(@Valid @RequestBody Account input) { return service.create(input); }
    @PutMapping("/{id}") public Account update(@PathVariable UUID id, @RequestBody Account input) { return service.update(id, input); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { service.delete(id); }
    @PostMapping("/migration/encrypt-legacy")
    public Map<String, Integer> encryptLegacy() { return Map.of("migrated", service.encryptLegacy()); }
}
