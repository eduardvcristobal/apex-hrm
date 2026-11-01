package com.apex.hrm.payroll.service;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FinalPayPaymentAuthorizationServiceTest {
    private static final UUID payrollEmployee=UUID.randomUUID();
    @Test void authorizedFinalPayAndMatchedPrimaryAccountAccepted(){
        assertDoesNotThrow(()->FinalPayPaymentAuthorizationService.requireReady(
            "APPROVED",new BigDecimal("5000.00"),"APPROVED",payrollEmployee,payrollEmployee,true));
    }
    @Test void cannotSendPaymentToDifferentEmployeesAccount(){
        assertEquals(409,assertThrows(ResponseStatusException.class,()->
            FinalPayPaymentAuthorizationService.requireReady("APPROVED",new BigDecimal("5000"),
            "APPROVED",payrollEmployee,UUID.randomUUID(),true)).getStatusCode().value());
    }
    @Test void pendingIdentityBlocksPayment(){
        assertThrows(ResponseStatusException.class,()->
            FinalPayPaymentAuthorizationService.requireReady("APPROVED",new BigDecimal("5000"),
            "PENDING",payrollEmployee,payrollEmployee,true));
    }
    @Test void onlyApprovedPositiveSettlementsEligible(){
        assertThrows(ResponseStatusException.class,()->
            FinalPayPaymentAuthorizationService.requireReady("PENDING",new BigDecimal("5000"),
            "APPROVED",payrollEmployee,payrollEmployee,true));
        assertThrows(ResponseStatusException.class,()->
            FinalPayPaymentAuthorizationService.requireReady("APPROVED",BigDecimal.ZERO,
            "APPROVED",payrollEmployee,payrollEmployee,true));
    }
    @Test void nonprimaryAccountBlocked(){
        assertThrows(ResponseStatusException.class,()->
            FinalPayPaymentAuthorizationService.requireReady("APPROVED",new BigDecimal("5000"),
            "APPROVED",payrollEmployee,payrollEmployee,false));
    }
    @Test void paymentAuthorizationRequiresDifferentActor(){
        UUID person=UUID.randomUUID();
        assertEquals(403,assertThrows(ResponseStatusException.class,()->
            FinalPayPaymentAuthorizationService.requireIndependentDecision("PREPARED",person,person))
            .getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->
            FinalPayPaymentAuthorizationService.requireIndependentDecision("AUTHORIZED",person,UUID.randomUUID()))
            .getStatusCode().value());
    }
}
