package com.apex.hrm.payroll.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.apex.hrm.common.BusinessException;
import com.apex.hrm.payroll.repo.AdjustmentRepository;
import com.apex.hrm.payroll.repo.PayPeriodRepository;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.platform.service.AuditService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
class AnnualAdjustmentReservationTest {
 @Test void genericAdjustmentEndpointCannotCreateReservedAnnualTaxItem() {
  var repo=mock(AdjustmentRepository.class);
  var period=mock(PayPeriodRepository.class);
  var employees=mock(EmployeeRepository.class);
  var audit=mock(AuditService.class);
  var svc=new AdjustmentService(repo,period,employees,audit);
  assertThrows(BusinessException.class,()->svc.create(UUID.randomUUID(),"WTAX_ANNUAL",
   "Annual tax","DEDUCTION",new BigDecimal("100.00"),false,"not permitted",null,null,UUID.randomUUID()));
  verifyNoInteractions(repo,period,employees,audit);
 }
}
