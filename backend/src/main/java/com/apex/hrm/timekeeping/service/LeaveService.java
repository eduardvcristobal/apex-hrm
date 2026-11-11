package com.apex.hrm.timekeeping.service;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.common.TenantContext;
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
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Leave workflow on top of an append-only ledger: balance = sum of ledger entries, never an editable number. */
@Service
public class LeaveService {
    private final LeaveRequestRepository requests;
    private final LeaveLedgerRepository ledger;
    private final LeaveTypeRepository types;
    private final EmployeeRepository employees;
    private final JdbcTemplate jdbc;

    public LeaveService(LeaveRequestRepository requests, LeaveLedgerRepository ledger, LeaveTypeRepository types,
                        EmployeeRepository employees, JdbcTemplate jdbc) {
        this.requests = requests;
        this.ledger = ledger;
        this.types = types;
        this.employees = employees;
        this.jdbc = jdbc;
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
        validateEntitlement(employeeId, leaveTypeId, type, from, to, days);
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
        validateEntitlement(r.getEmployeeId(), r.getLeaveTypeId(), type, r.getDateFrom(), r.getDateTo(), r.getDays());
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

    private record BenefitRule(UUID id,UUID profileId,BigDecimal annualEntitlement,String grantBasis,boolean paid,boolean convertible,BigDecimal carryOverMax) {}

    private void validateEntitlement(UUID employeeId,UUID leaveTypeId,LeaveType type,LocalDate from,LocalDate to,BigDecimal days) {
        UUID tenant=TenantContext.get();
        if(tenant!=null) {
            UUID startProfile=activeProfile(tenant,employeeId,from);
            UUID endProfile=activeProfile(tenant,employeeId,to);
            if(!Objects.equals(startProfile,endProfile)) throw new BusinessException("A leave request cannot cross an effective-dated benefit profile change");
            if(startProfile!=null) {
                BenefitRule rule=benefitRule(tenant,startProfile,leaveTypeId,from);
                BenefitRule endRule=benefitRule(tenant,startProfile,leaveTypeId,to);
                if(rule==null) throw new BusinessException("This employee's active benefit profile does not include " + type.getCode());
                if(endRule==null||!rule.id().equals(endRule.id())) throw new BusinessException("A leave request cannot cross an effective-dated leave entitlement change");
                if(rule.annualEntitlement().signum()==0) throw new BusinessException("This leave type is not available under the employee's active benefit profile");
                if(days.compareTo(ledger.balanceAt(employeeId,leaveTypeId,from))>0) throw new BusinessException("Insufficient " + type.getCode() + " balance");
                return;
            }
        }
        if(("ML".equals(type.getCode())||"PL".equals(type.getCode()))&&tenant!=null) throw new BusinessException(type.getCode()+" requires an active event-based benefit profile and an approved event claim");
        if(type.getAnnualEntitlement()!=null&&days.compareTo(balance(employeeId,leaveTypeId))>0) throw new BusinessException("Insufficient " + type.getCode() + " balance");
    }

    public boolean paidFor(UUID employeeId,UUID leaveTypeId,LocalDate date,boolean fallback) {
        UUID tenant=TenantContext.get();
        if(tenant==null) return fallback;
        UUID profile=activeProfile(tenant,employeeId,date);
        if(profile==null) return fallback;
        List<Boolean> values=jdbc.query("SELECT paid FROM leave_benefit_entitlement WHERE tenant_id=? AND profile_id=? AND leave_type_id=? AND effective_from<=? AND (effective_to IS NULL OR effective_to>=?) ORDER BY effective_from DESC LIMIT 1",(rs,n)->rs.getBoolean(1),tenant,profile,leaveTypeId,date,date);
        return !values.isEmpty()&&values.get(0);
    }

    private UUID activeProfile(UUID tenant,UUID employee,LocalDate date) {
        List<UUID> profiles=jdbc.query("SELECT profile_id FROM employee_leave_benefit_assignment WHERE tenant_id=? AND employee_id=? AND effective_from<=? AND (effective_to IS NULL OR effective_to>=?) ORDER BY effective_from DESC LIMIT 1",(rs,n)->rs.getObject(1,UUID.class),tenant,employee,date,date);
        return profiles.isEmpty()?null:profiles.get(0);
    }

    private BenefitRule benefitRule(UUID tenant,UUID profile,UUID leaveType,LocalDate date) {
        return jdbc.query("SELECT id,profile_id,annual_entitlement,grant_basis,paid,convertible,carry_over_max FROM leave_benefit_entitlement WHERE tenant_id=? AND profile_id=? AND leave_type_id=? AND effective_from<=? AND (effective_to IS NULL OR effective_to>=?) ORDER BY effective_from DESC LIMIT 1",rs->rs.next()?new BenefitRule(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getBigDecimal(3),rs.getString(4),rs.getBoolean(5),rs.getBoolean(6),rs.getBigDecimal(7)):null,tenant,profile,leaveType,date,date);
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
