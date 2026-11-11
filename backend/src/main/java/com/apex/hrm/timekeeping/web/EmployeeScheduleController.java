package com.apex.hrm.timekeeping.web;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.ReadController;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.timekeeping.domain.EmployeeSchedule;
import com.apex.hrm.timekeeping.repo.EmployeeScheduleRepository;
import com.apex.hrm.timekeeping.repo.WorkScheduleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/employee-schedules")
public class EmployeeScheduleController extends ReadController<EmployeeSchedule> {
    private final EmployeeScheduleRepository schedules;
    private final EmployeeRepository employees;
    private final WorkScheduleRepository workSchedules;
    private final ObjectMapper mapper;

    public EmployeeScheduleController(EmployeeScheduleRepository schedules, EmployeeRepository employees,
                                      WorkScheduleRepository workSchedules, ObjectMapper mapper) {
        super(schedules);
        this.schedules = schedules;
        this.employees = employees;
        this.workSchedules = workSchedules;
        this.mapper = mapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public EmployeeSchedule create(@RequestBody EmployeeSchedule input) {
        validate(input, null);
        return schedules.save(input);
    }

    @PutMapping("/{id}")
    @Transactional
    public EmployeeSchedule update(@PathVariable UUID id, @RequestBody Map<String, Object> patch) {
        EmployeeSchedule current = get(id);
        patch.keySet().removeAll(java.util.Set.of("id", "tenantId", "createdAt", "updatedAt", "version"));
        try {
            mapper.updateValue(current, patch);
        } catch (JsonProcessingException e) {
            throw new BusinessException("Invalid schedule assignment update: " + e.getOriginalMessage());
        }
        validate(current, id);
        return schedules.save(current);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID id) {
        schedules.delete(get(id));
    }

    private void validate(EmployeeSchedule assignment, UUID exceptId) {
        if (assignment.getEmployeeId() == null || employees.findById(assignment.getEmployeeId()).isEmpty()) {
            throw new BusinessException("Choose an existing employee for the schedule assignment");
        }
        if (assignment.getScheduleId() == null || workSchedules.findById(assignment.getScheduleId()).isEmpty()) {
            throw new BusinessException("Choose an existing work schedule");
        }
        if (assignment.getEffectiveFrom() == null
                || (assignment.getEffectiveTo() != null && assignment.getEffectiveTo().isBefore(assignment.getEffectiveFrom()))) {
            throw new BusinessException("Schedule assignment end date must be on or after its start date");
        }
        var employee = employees.findById(assignment.getEmployeeId()).orElseThrow();
        if (employee.getHireDate() != null && assignment.getEffectiveFrom().isBefore(employee.getHireDate())) {
            throw new BusinessException("Schedule assignment cannot start before the employee's hire date");
        }
        LocalDate through = assignment.getEffectiveTo() == null ? LocalDate.of(9999, 12, 31) : assignment.getEffectiveTo();
        boolean overlaps = schedules.findOverlapping(assignment.getEffectiveFrom(), through).stream()
                .anyMatch(existing -> existing.getEmployeeId().equals(assignment.getEmployeeId())
                        && !existing.getId().equals(exceptId));
        if (overlaps) {
            throw new BusinessException("This employee already has a schedule assigned for part of those dates. End the current assignment first.");
        }
    }
}
