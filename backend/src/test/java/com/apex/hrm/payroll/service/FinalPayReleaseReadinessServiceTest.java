package com.apex.hrm.payroll.service;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FinalPayReleaseReadinessServiceTest {
    @Test void evenSignedExternallyClaimedDigestAndInternalReviewsCannotAuthorizePayout(){
        var reasons=FinalPayReleaseReadinessService.blockingReasons(true,true,
            "SOURCE_REVIEWED","INTERNALLY_REVIEWED","ISSUER_SIGNATURE_VALID_FOR_DIGEST",
            "MANUAL_EVIDENCE_REVIEWED");
        assertTrue(reasons.stream().anyMatch(s->s.contains("Actual external document bytes")));
        assertTrue(reasons.stream().anyMatch(s->s.contains("Bank account ownership")));
        assertTrue(reasons.stream().anyMatch(s->s.contains("Statutory final-pay")));
        assertTrue(reasons.stream().anyMatch(s->s.contains("Bank statement settlement")));
    }
    @Test void missingStagesProduceMultipleIndependentBlockers(){
        var reasons=FinalPayReleaseReadinessService.blockingReasons(false,false,
            "REJECTED","REJECTED","NOT_VERIFIED","PAYMENT_NOT_PREPARED");
        assertTrue(reasons.size()>=9);
        assertTrue(reasons.stream().anyMatch(s->s.contains("immutable payroll")));
    }
}
