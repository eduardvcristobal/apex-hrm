package com.apex.hrm.common;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionHandlerStatusTest {
    @Test void missingCrossTenantRecordStays404(){
        var handled=new GlobalExceptionHandler().responseStatus(
            new ResponseStatusException(HttpStatus.NOT_FOUND,"Tenant-scoped record not found"));
        assertEquals(404,handled.getStatusCode().value());
        assertEquals(404,handled.getBody().status());
        assertEquals("Tenant-scoped record not found",handled.getBody().message());
    }
    @Test void twoPersonViolationStays403(){
        var handled=new GlobalExceptionHandler().responseStatus(
            new ResponseStatusException(HttpStatus.FORBIDDEN,"Independent approver required"));
        assertEquals(403,handled.getStatusCode().value());
    }
    @Test void duplicateRequestStays409(){
        var handled=new GlobalExceptionHandler().responseStatus(
            new ResponseStatusException(HttpStatus.CONFLICT,"Duplicate financial decision"));
        assertEquals(409,handled.getStatusCode().value());
    }
}
