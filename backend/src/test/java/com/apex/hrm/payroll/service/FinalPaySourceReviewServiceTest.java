package com.apex.hrm.payroll.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FinalPaySourceReviewServiceTest {
    @Test void sourceCapturerCannotBeIndependentReviewer(){
        UUID capturedBy=UUID.randomUUID();
        assertEquals(403,assertThrows(ResponseStatusException.class,()->
            FinalPaySourceReviewService.requireIndependentCapture(capturedBy,capturedBy))
            .getStatusCode().value());
        assertDoesNotThrow(()->FinalPaySourceReviewService.requireIndependentCapture(
            capturedBy,UUID.randomUUID()));
    }
    @Test void requesterCannotIndependentlyReviewTheirOwnSnapshot(){
        UUID actor=UUID.randomUUID();
        assertEquals(403,assertThrows(ResponseStatusException.class,()->
            FinalPaySourceReviewService.requireIndependentDecision("PENDING",actor,actor))
            .getStatusCode().value());
    }
    @Test void secondReviewCannotRewriteAnExistingDecision(){
        assertEquals(409,assertThrows(ResponseStatusException.class,()->
            FinalPaySourceReviewService.requireIndependentDecision(
                "SOURCE_REVIEWED",UUID.randomUUID(),UUID.randomUUID())).getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->
            FinalPaySourceReviewService.requireIndependentDecision(
                "REJECTED",UUID.randomUUID(),UUID.randomUUID())).getStatusCode().value());
    }
    @Test void differentReviewerMayDecidePendingReview(){
        assertDoesNotThrow(()->FinalPaySourceReviewService.requireIndependentDecision(
            "PENDING",UUID.randomUUID(),UUID.randomUUID()));
    }
}
