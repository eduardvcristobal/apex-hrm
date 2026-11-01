package com.apex.hrm.timekeeping.service;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.timekeeping.domain.LeaveLedger;
import com.apex.hrm.timekeeping.domain.LeaveRequest;
import com.apex.hrm.timekeeping.domain.LeaveType;
import com.apex.hrm.timekeeping.repo.LeaveLedgerRepository;
import com.apex.hrm.timekeeping.repo.LeaveRequestRepository;
import com.apex.hrm.timekeeping.repo.LeaveTypeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Leave workflow on top of an append-only ledger: balance = sum of ledger entries, never an editable number. */
@Service
public class LeaveService {
    private final LeaveRequestRepository requests;
    private final LeaveLedgerRepository ledger;
    private final LeaveTypeRepository types;
    private final EmployeeRepository employees;

    public LeaveService(LeaveRequestRepository requests, LeaveLedgerRepository ledger, LeaveTypeRepository types,
                        EmployeeRepository employees) {
        this.requests = requests;
        this.ledger = ledger;
        this.types = types;
        this.employees = employees;
    }

    @Transactional
    public LeaveRequest file(UUID employeeId, UUID leaveTypeId, LocalDate from, LocalDate to, BigDecimal days,
                             boolean halfDay, String reason) {
        employees.findById(employeeId).orElseThrow(() -> new NotFoundException("Employee", employeeId));
        LeaveType type = types.findById(leaveTypeId).orElseThrow(() -> new NotFoundException("Leave type", leaveTypeId));
        if (from == null || to == null || to.isBefore(from)) {
            throw new BusinessException("Invalid leave dates");
        }
        if (days == null || days.signum() <= 0) {
            throw new BusinessException("days must be greater than zero");
        }
        if (type.getAnnualEntitlement() != null && days.compareTo(balance(employeeId, leaveTypeId)) > 0) {
            throw new BusinessException("Insufficient " + type.getCode() + " balance");
        }
        LeaveRequest r = new LeaveRequest();
        r.setEmployeeId(employeeId);
        r.setLeaveTypeId(leaveTypeId);
        r.setDateFrom(from);
        r.setDateTo(to);
        r.setDays(days);
        r.setHalfDay(halfDay);
        r.setReason(reason);
        return requests.save(r);
    }

    @Transactional
    public LeaveRequest approve(UUID id, UUID approverId) {
        LeaveRequest r = load(id);
        requireStatus(r, "PENDING");
        LeaveType type = types.findById(r.getLeaveTypeId()).orElseThrow();
        if (type.getAnnualEntitlement() != null && r.getDays().compareTo(balance(r.getEmployeeId(), r.getLeaveTypeId())) > 0) {
            throw new BusinessException("Insufficient " + type.getCode() + " balance");
        }
        addEntry(r.getEmployeeId(), r.getLeaveTypeId(), r.getDateFrom(), "USED", r.getDays().negate(), r.getId(), "Leave approved");
        r.setStatus("APPROVED");
        r.setApprovedBy(approverId);
        r.setApprovedAt(Instant.now());
        return requests.save(r);
    }

    @Transactional
    public LeaveRequest reject(UUID id, UUID approverId) {
        LeaveRequest r = load(id);
        requireStatus(r, "PENDING");
        r.setStatus("REJECTED");
        r.setApprovedBy(approverId);
        r.setApprovedAt(Instant.now());
        return requests.save(r);
    }

    @Transactional
    public LeaveRequest cancel(UUID id) {
        LeaveRequest r = load(id);
        if ("APPROVED".equals(r.getStatus())) {
            addEntry(r.getEmployeeId(), r.getLeaveTypeId(), LocalDate.now(), "REVERSAL", r.getDays(), r.getId(), "Leave cancelled");
        } else if (!"PENDING".equals(r.getStatus())) {
            throw new BusinessException("Cannot cancel a " + r.getStatus() + " request");
        }
        r.setStatus("CANCELLED");
        return requests.save(r);
    }

    /** Credits days (accrual, annual grant, carry-over). */
    @Transactional
    public LeaveLedger grant(UUID employeeId, UUID leaveTypeId, BigDecimal days, String remarks) {
        if (days == null || days.signum() <= 0) {
            throw new BusinessException("days must be greater than zero");
        }
        employees.findById(employeeId).orElseThrow(() -> new NotFoundException("Employee", employeeId));
        types.findById(leaveTypeId).orElseThrow(() -> new NotFoundException("Leave type", leaveTypeId));
        return addEntry(employeeId, leaveTypeId, LocalDate.now(), "ACCRUAL", days, null, remarks);
    }

    @Transactional(readOnly = true)
    public BigDecimal balance(UUID employeeId, UUID leaveTypeId) {
        return ledger.balance(employeeId, leaveTypeId);
    }

    private LeaveLedger addEntry(UUID employeeId, UUID typeId, LocalDate date, String entryType, BigDecimal days,
                                 UUID refId, String remarks) {
        LeaveLedger l = new LeaveLedger();
        l.setEmployeeId(employeeId);
        l.setLeaveTypeId(typeId);
        l.setEntryDate(date);
        l.setEntryType(entryType);
        l.setDays(days);
        l.setReferenceType(refId == null ? null : "LEAVE_REQUEST");
        l.setReferenceId(refId);
        l.setRemarks(remarks);
        return ledger.save(l);
    }

    private LeaveRequest load(UUID id) {
        return requests.findById(id).orElseThrow(() -> new NotFoundException("Leave request", id));
    }

    private static void requireStatus(LeaveRequest r, String expected) {
        if (!expected.equals(r.getStatus())) {
            throw new BusinessException("Leave request is " + r.getStatus() + ", expected " + expected);
        }
    }
}
