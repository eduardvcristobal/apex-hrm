package com.apex.hrm.payroll.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
class PayrollIdentityLinkServiceTest {
    private final UUID company=UUID.randomUUID();
    private PayrollIdentityLinkService.Candidate person(String number,String last,String first,UUID co) {
        return new PayrollIdentityLinkService.Candidate(co,number,first,last,LocalDate.of(2021,5,3),"ACTIVE");
    }
    @Test void allowsExactCompanyNumberNameAndHireDateWithWhitespaceNormalization(){
        assertDoesNotThrow(()->PayrollIdentityLinkService.confirmCandidateMatch(
            person("A001","De la Cruz","Anna",company),
            person("A001","DE  LA  CRUZ","ANNA",company)));
    }
    @Test void blocksSameNameInOtherCompany(){
        assertThrows(ResponseStatusException.class,()->PayrollIdentityLinkService.confirmCandidateMatch(
            person("A001","Cruz","Anna",company),person("A001","Cruz","Anna",UUID.randomUUID())));
    }
    @Test void blocksDifferentEmployeeNumberDespiteMatchingNames(){
        assertThrows(ResponseStatusException.class,()->PayrollIdentityLinkService.confirmCandidateMatch(
            person("A001","Cruz","Anna",company),person("A002","Cruz","Anna",company)));
    }
    @Test void blocksSameEmployeeNumberWithDifferentLegalName(){
        assertThrows(ResponseStatusException.class,()->PayrollIdentityLinkService.confirmCandidateMatch(
            person("A001","Cruz","Anna",company),person("A001","Cruz","Ariana",company)));
    }
    @Test void requiresIndependentApproverAndPendingStatus(){
        UUID requester=UUID.randomUUID();
        assertEquals(403,assertThrows(ResponseStatusException.class,()->PayrollIdentityLinkService
            .requireIndependentReview("PENDING",requester,requester)).getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->PayrollIdentityLinkService
            .requireIndependentReview("APPROVED",requester,UUID.randomUUID())).getStatusCode().value());
        assertDoesNotThrow(()->PayrollIdentityLinkService.requireIndependentReview(
            "PENDING",requester,UUID.randomUUID()));
    }
}
