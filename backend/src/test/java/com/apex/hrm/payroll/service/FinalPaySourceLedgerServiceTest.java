package com.apex.hrm.payroll.service;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FinalPaySourceLedgerServiceTest {
    @Test void mismatchedLoanBalancesNeverPassReview(){
        assertEquals("LOAN_BALANCE_MISMATCH",FinalPaySourceLedgerService.loanStatus(
            2,new BigDecimal("1200.00"),new BigDecimal("1000.00")));
    }
    @Test void exactLoanBalanceStillNeedsIndependentSignoff(){
        assertEquals("MATCHED_ACTIVE_BALANCE_REVIEW_REQUIRED",FinalPaySourceLedgerService.loanStatus(
            1,new BigDecimal("1200.0000"),new BigDecimal("1200.00")));
    }
    @Test void absenceOfLoanLedgerDoesNotProveDebtForgiveness(){
        assertEquals("NO_ACTIVE_LOANS_REVIEW_REQUIRED",FinalPaySourceLedgerService.loanStatus(
            0,BigDecimal.ZERO,BigDecimal.ZERO));
        assertEquals("LOAN_SOURCE_MISSING",FinalPaySourceLedgerService.loanStatus(
            0,BigDecimal.ZERO,BigDecimal.TEN));
    }
    @Test void convertibleLeaveDaysNeverAutomaticallyBecomeMonetaryPayout(){
        assertEquals("CONVERSION_RATE_AND_ELIGIBILITY_REVIEW_REQUIRED",
            FinalPaySourceLedgerService.leaveStatus(2,new BigDecimal("4.5"),new BigDecimal("3000")));
        assertEquals("LEAVE_SOURCE_MISSING",
            FinalPaySourceLedgerService.leaveStatus(0,BigDecimal.ZERO,BigDecimal.TEN));
    }
}
