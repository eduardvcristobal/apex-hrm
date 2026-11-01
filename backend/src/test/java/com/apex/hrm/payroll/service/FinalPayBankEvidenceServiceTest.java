package com.apex.hrm.payroll.service;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FinalPayBankEvidenceServiceTest {
    @Test void authorizedPaymentEvidenceMustMatchExactly() {
        assertDoesNotThrow(()->FinalPayBankEvidenceService.verifyAuthorizedPayment(
            "AUTHORIZED",new BigDecimal("5000.00"),new BigDecimal("5000.0000")));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->
            FinalPayBankEvidenceService.verifyAuthorizedPayment(
                "AUTHORIZED",new BigDecimal("5000.00"),new BigDecimal("4999.99")))
                .getStatusCode().value());
    }
    @Test void bankEvidenceCannotBeReviewedBeforeAuthorization() {
        assertThrows(ResponseStatusException.class,()->
            FinalPayBankEvidenceService.verifyAuthorizedPayment(
                "PREPARED",new BigDecimal("5000"),new BigDecimal("5000")));
    }
    @Test void reviewerCannotBeRecorderOrPaymentAuthorizer() {
        UUID recorder=UUID.randomUUID(), authorizer=UUID.randomUUID();
        assertEquals(403,assertThrows(ResponseStatusException.class,()->
            FinalPayBankEvidenceService.requireIndependentEvidenceReview(
                "SUBMITTED",recorder,authorizer,recorder)).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->
            FinalPayBankEvidenceService.requireIndependentEvidenceReview(
                "SUBMITTED",recorder,authorizer,authorizer)).getStatusCode().value());
        assertDoesNotThrow(()->FinalPayBankEvidenceService.requireIndependentEvidenceReview(
            "SUBMITTED",recorder,authorizer,UUID.randomUUID()));
    }
    @Test void evidenceCannotBeReviewedTwice() {
        assertEquals(409,assertThrows(ResponseStatusException.class,()->
            FinalPayBankEvidenceService.requireIndependentEvidenceReview(
                "EVIDENCE_REVIEWED",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()))
                .getStatusCode().value());
    }
}
