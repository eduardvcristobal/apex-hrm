package com.apex.hrm.payroll.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FinalPaySourceSnapshotServiceTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LocalDate from=LocalDate.of(2026,9,1),to=LocalDate.of(2026,9,5);
    @Test void cutoffCoverageRejectsGapsButDoesNotDoubleCountOverlaps() throws Exception {
        JsonNode periods=mapper.readTree("""
            [{"from":"2026-09-01","to":"2026-09-02","status":"LOCKED"},
             {"from":"2026-09-02","to":"2026-09-04","status":"PAID"},
             {"from":"2026-09-05","to":"2026-09-05","status":"DRAFT"}]
            """);
        assertEquals(1,FinalPaySourceSnapshotService.gapDays(periods,"from","to",from,to,true));
    }
    @Test void compensationEffectiveDateGapsAndOpenEndedRanges() throws Exception{
        JsonNode rows=mapper.readTree("""
            [{"from":"2026-09-01","to":"2026-09-02","rate":500},
             {"from":"2026-09-04","to":null,"rate":600}]
            """);
        assertEquals(1,FinalPaySourceSnapshotService.gapDays(rows,"from","to",from,to,false));
    }
    @Test void statusAndDigestAreDeterministic(){
        assertFalse(FinalPaySourceSnapshotService.isFinal("OPEN"));
        assertTrue(FinalPaySourceSnapshotService.isFinal("LOCKED"));
        assertEquals(FinalPaySourceSnapshotService.sha256("{\"a\":1}"),
                     FinalPaySourceSnapshotService.sha256("{\"a\":1}"));
        assertNotEquals(FinalPaySourceSnapshotService.sha256("{\"a\":1}"),
                        FinalPaySourceSnapshotService.sha256("{\"a\":2}"));
    }
}
