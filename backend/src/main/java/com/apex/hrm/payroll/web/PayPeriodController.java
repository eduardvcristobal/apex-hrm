package com.apex.hrm.payroll.web;

import com.apex.hrm.common.CrudController;
import com.apex.hrm.payroll.domain.PayPeriod;
import com.apex.hrm.payroll.repo.PayPeriodRepository;
import com.apex.hrm.payroll.service.PayPeriodCalendarService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pay-periods")
public class PayPeriodController extends CrudController<PayPeriod> {
    public record GenerateRequest(UUID payGroupId, LocalDate from, LocalDate through, int payDateOffsetDays) {}
    private final PayPeriodCalendarService calendar;

    public PayPeriodController(PayPeriodRepository repo, ObjectMapper mapper, PayPeriodCalendarService calendar) {
        super(repo, mapper);
        this.calendar = calendar;
    }

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public PayPeriodCalendarService.Result generate(@RequestBody GenerateRequest request) {
        return calendar.generate(request.payGroupId(), request.from(), request.through(), request.payDateOffsetDays());
    }
}
