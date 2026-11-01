package com.apex.hrm.timekeeping.service;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.timekeeping.domain.OvertimeRequest;
import com.apex.hrm.timekeeping.repo.OvertimeRequestRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Overtime is paid only up to the approved minutes, and only for time actually detected in the DTR. */
@Service
public class OvertimeService {
    private final OvertimeRequestRepository requests;
    private final EmployeeRepository employees;

    public OvertimeService(OvertimeRequestRepository requests, EmployeeRepository employees) {
        this.requests = requests;
        this.employees = employees;
    }

    @Transactional
    public OvertimeRequest file(UUID employeeId, LocalDate workDate, int minutes, String reason) {
        employees.findById(employeeId).orElseThrow(() -> new NotFoundException("Employee", employeeId));
        if (workDate == null || minutes <= 0) {
            throw new BusinessException("workDate and positive minutes are required");
        }
        OvertimeRequest r = new OvertimeRequest();
        r.setEmployeeId(employeeId);
        r.setWorkDate(workDate);
        r.setMinutesRequested(minutes);
        r.setReason(reason);
        return requests.save(r);
    }

    @Transactional
    public OvertimeRequest approve(UUID id, Integer approvedMinutes, UUID approverId) {
        OvertimeRequest r = pending(id);
        int minutes = approvedMinutes == null ? r.getMinutesRequested() : approvedMinutes;
        if (minutes <= 0 || minutes > r.getMinutesRequested()) {
            throw new BusinessException("approvedMinutes must be between 1 and " + r.getMinutesRequested());
        }
        r.setStatus("APPROVED");
        r.setApprovedMinutes(minutes);
        r.setApprovedBy(approverId);
        r.setApprovedAt(Instant.now());
        return requests.save(r);
    }

    @Transactional
    public OvertimeRequest reject(UUID id, UUID approverId) {
        OvertimeRequest r = pending(id);
        r.setStatus("REJECTED");
        r.setApprovedBy(approverId);
        r.setApprovedAt(Instant.now());
        return requests.save(r);
    }

    private OvertimeRequest pending(UUID id) {
        OvertimeRequest r = requests.findById(id).orElseThrow(() -> new NotFoundException("Overtime request", id));
        if (!"PENDING".equals(r.getStatus())) {
            throw new BusinessException("Overtime request is already " + r.getStatus());
        }
        return r;
    }
}
