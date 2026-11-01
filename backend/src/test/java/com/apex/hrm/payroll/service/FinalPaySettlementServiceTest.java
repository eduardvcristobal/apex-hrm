package com.apex.hrm.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import com.apex.hrm.payroll.engine.FinalPayCalculator;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class FinalPaySettlementServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TenantJdbcExecutor tenants = mock(TenantJdbcExecutor.class);
    private final FinalPaySettlementService service = new FinalPaySettlementService(jdbc,tenants);
    private final HrmUserPrincipal user = new HrmUserPrincipal(UUID.randomUUID(),
            UUID.randomUUID(),"payroll@example.test",null,true,java.util.List.of());
    private static BigDecimal amount(String x) { return new BigDecimal(x); }
    private static FinalPayCalculator.Input amounts(String unpaidBasic) {
        return new FinalPayCalculator.Input(amount(unpaidBasic),amount("1300"),amount("600"),
            amount("0"),amount("100"),amount("300"),amount("0"),amount("200"),amount("0"));
    }
    private FinalPaySettlementService.Submission request(FinalPayCalculator.Input values,
            boolean leave, boolean loans, boolean taxes, boolean separation) {
        return new FinalPaySettlementService.Submission(values,"HR handoff and signed ledger",
                "Validated benefits and final pay source documents for this separation.",
                leave,loans,taxes,separation);
    }

    @Test void rejectsIncompleteAttestationBeforeDatabaseAccess() {
        var input=request(amounts("15000"),true,false,true,true);
        var failure=assertThrows(ResponseStatusException.class,()->service.submit(user,
            UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),input));
        assertEquals(400,failure.getStatusCode().value());
        verifyNoInteractions(tenants,jdbc);
    }

    @Test void rejectsNegativeSettlementRequiringRecoveryWorkflow() {
        var input=request(amounts("0"),true,true,true,true);
        // Even with a nonzero accrued 13th-month amount, a sufficiently large
        // outstanding loan must never be silently clamped to zero.
        var deductions=new FinalPayCalculator.Input(amount("0"),amount("0"),amount("0"),
            amount("0"),amount("0"),amount("5000"),amount("0"),amount("0"),amount("0"));
        var failure=assertThrows(ResponseStatusException.class,()->service.submit(user,
            UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
            request(deductions,true,true,true,true)));
        assertEquals(409,failure.getStatusCode().value());
        verifyNoInteractions(tenants,jdbc);
    }

    @Test void requiresDocumentedAccountingReference() {
        var input=new FinalPaySettlementService.Submission(amounts("15000"),"",
                "Final pay review signed by HR/payroll and source documents attached.",
                true,true,true,true);
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.submit(user,
            UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),input))
            .getStatusCode().value());
        verifyNoInteractions(tenants,jdbc);
    }

    @Test void previewPreservesFullGrossAndAllAuthorizedDeductions() {
        var result=FinalPayCalculator.preview(amounts("15000"));
        assertEquals(amount("17000.00"),result.gross());
        assertEquals(amount("500.00"),result.deductions());
        assertEquals(amount("16500.00"),result.net());
    }
}
