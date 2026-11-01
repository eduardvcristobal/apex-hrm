package com.apex.hrm.common;

import java.util.UUID;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String what, UUID id) {
        super(what + " not found: " + id);
    }
}
