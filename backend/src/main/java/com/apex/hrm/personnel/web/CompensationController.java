package com.apex.hrm.personnel.web;

import com.apex.hrm.personnel.domain.Compensation;
import com.apex.hrm.personnel.service.CompensationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/employees/{employeeId}/compensation")
public class CompensationController {
    public record ChangeRequest(LocalDate effectiveFrom, String payBasis, BigDecimal baseRate, String reason) {
    }

    private final CompensationService service;

    public CompensationController(CompensationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Compensation> history(@PathVariable UUID employeeId) {
        return service.history(employeeId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Compensation change(@PathVariable UUID employeeId, @RequestBody ChangeRequest r) {
        return service.change(employeeId, r.effectiveFrom(), r.payBasis(), r.baseRate(), r.reason());
    }
}
