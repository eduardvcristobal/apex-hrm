package com.apex.hrm.payroll.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FinalPayPeriodAttestationServiceTest {
    @Test void sha256DocumentReferenceIsStrictAndUnverified(){
        assertEquals(400,assertThrows(ResponseStatusException.class,
            ()->FinalPayPeriodAttestationService.requireDigest("abcd")).getStatusCode().value());
        assertEquals("a".repeat(64),FinalPayPeriodAttestationService.requireDigest("a".repeat(64)));
    }
    @Test void internalReviewIsIndependentOfSourceAndCaptureRoles(){
        UUID requester=UUID.randomUUID(),capturer=UUID.randomUUID(),sourceReviewer=UUID.randomUUID();
        for(UUID forbidden:new UUID[]{requester,capturer,sourceReviewer}){
            assertEquals(403,assertThrows(ResponseStatusException.class,
                ()->FinalPayPeriodAttestationService.requireIndependent(
                    "PENDING",requester,capturer,sourceReviewer,forbidden)).getStatusCode().value());
        }
        assertDoesNotThrow(()->FinalPayPeriodAttestationService.requireIndependent(
            "PENDING",requester,capturer,sourceReviewer,UUID.randomUUID()));
        assertEquals(409,assertThrows(ResponseStatusException.class,
            ()->FinalPayPeriodAttestationService.requireIndependent(
                "REJECTED",requester,capturer,sourceReviewer,UUID.randomUUID())).getStatusCode().value());
    }
    @Test void incompleteOrStaleSourcesCannotBeInternallyReviewed(){
        for(String status:new String[]{"PENDING","REJECTED"}){
            assertEquals(409,assertThrows(ResponseStatusException.class,
                ()->FinalPayPeriodAttestationService.requireClosureEligible(
                    status,"READY_FOR_INDEPENDENT_REVIEW",true,true,true)).getStatusCode().value());
        }
        for(String status:new String[]{"INCOMPLETE_SOURCE","STALE_SOURCE","SUPERSEDED_SOURCE"}){
            assertEquals(409,assertThrows(ResponseStatusException.class,
                ()->FinalPayPeriodAttestationService.requireClosureEligible(
                    "SOURCE_REVIEWED",status,true,true,true)).getStatusCode().value());
        }
        assertEquals(409,assertThrows(ResponseStatusException.class,
            ()->FinalPayPeriodAttestationService.requireClosureEligible(
                "SOURCE_REVIEWED","READY_FOR_INDEPENDENT_REVIEW",true,true,false)).getStatusCode().value());
        assertDoesNotThrow(()->FinalPayPeriodAttestationService.requireClosureEligible(
            "SOURCE_REVIEWED","READY_FOR_INDEPENDENT_REVIEW",true,true,true));
    }
}
