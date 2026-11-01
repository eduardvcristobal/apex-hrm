package com.apex.hrm.payroll.service;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinalPayReconciliationServiceTest {
    private final UUID payrollEmployee=UUID.randomUUID();
    private String check(String settlement,BigDecimal net,String payment,BigDecimal amount,
            String identity,UUID bankAccountOwner,boolean primary,String evidence,BigDecimal reported){
        return FinalPayReconciliationService.classify(settlement,net,payment,amount,
            identity,payrollEmployee,bankAccountOwner,primary,evidence,reported);
    }
    @Test void positiveReviewedManualEvidenceIsNotEquivalentToBankPayment(){
        assertEquals("MANUAL_EVIDENCE_REVIEWED",check("APPROVED",new BigDecimal("1250.00"),
            "AUTHORIZED",new BigDecimal("1250.0000"),"APPROVED",payrollEmployee,true,
            "EVIDENCE_REVIEWED",new BigDecimal("1250")));
    }
    @Test void blocksUnknownOrUnapprovedSettlement(){
        assertEquals("SETTLEMENT_NOT_APPROVED",check("PENDING",BigDecimal.TEN,
            null,null,null,null,false,null,null));
    }
    @Test void blocksMismatchedOrWrongEmployeeBankAccount(){
        assertEquals("IDENTITY_OR_AMOUNT_MISMATCH",check("APPROVED",BigDecimal.TEN,
            "AUTHORIZED",BigDecimal.ONE,"APPROVED",payrollEmployee,true,null,null));
        assertEquals("IDENTITY_OR_AMOUNT_MISMATCH",check("APPROVED",BigDecimal.TEN,
            "AUTHORIZED",BigDecimal.TEN,"APPROVED",UUID.randomUUID(),true,null,null));
    }
    @Test void awaitingPaymentOrEvidenceCannotBeCertified(){
        assertEquals("PAYMENT_NOT_PREPARED",check("APPROVED",BigDecimal.TEN,
            null,null,null,null,false,null,null));
        assertEquals("PAYMENT_NOT_AUTHORIZED",check("APPROVED",BigDecimal.TEN,
            "PREPARED",BigDecimal.TEN,"APPROVED",payrollEmployee,true,null,null));
        assertEquals("BANK_EVIDENCE_MISSING",check("APPROVED",BigDecimal.TEN,
            "AUTHORIZED",BigDecimal.TEN,"APPROVED",payrollEmployee,true,null,null));
    }
    @Test void evidenceMismatchAndUnreviewedStateRemainNoGo(){
        assertEquals("BANK_EVIDENCE_AMOUNT_MISMATCH",check("APPROVED",BigDecimal.TEN,
            "AUTHORIZED",BigDecimal.TEN,"APPROVED",payrollEmployee,true,
            "EVIDENCE_REVIEWED",BigDecimal.ONE));
        assertEquals("BANK_EVIDENCE_NOT_APPROVED",check("APPROVED",BigDecimal.TEN,
            "AUTHORIZED",BigDecimal.TEN,"APPROVED",payrollEmployee,true,
            "SUBMITTED",BigDecimal.TEN));
    }
}
