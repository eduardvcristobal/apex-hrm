package com.apex.hrm.payroll.service;

import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankExportCipherTest {
    private static String key(int fill) {
        byte[] raw = new byte[32];
        Arrays.fill(raw, (byte) fill);
        return Base64.getEncoder().encodeToString(raw);
    }
    private static final UUID TENANT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID RUN = UUID.fromString("20000000-0000-4000-8000-000000000001");

    @Test
    void encryptsAndAuthenticatesTenantRunAndFilename() {
        BankExportCipher cipher = new BankExportCipher(key(1), "k1", "", "");
        String csv = "ACCOUNT_NO,AMOUNT\n001122334455,100.00\n";
        String a = cipher.encrypt(csv, TENANT, RUN, "payroll.csv");
        String b = cipher.encrypt(csv, TENANT, RUN, "payroll.csv");
        assertTrue(a.startsWith("e1:k1:"));
        assertNotEquals(a, b);
        assertFalse(a.contains("001122334455"));
        assertEquals(csv, cipher.decrypt(a, TENANT, RUN, "payroll.csv"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(a, UUID.randomUUID(), RUN, "payroll.csv"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(a, TENANT, UUID.randomUUID(), "payroll.csv"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(a, TENANT, RUN, "another.csv"));
    }

    @Test
    void detectsTamperingAndWrongKeys() {
        BankExportCipher cipher = new BankExportCipher(key(2), "k1", "", "");
        String original = cipher.encrypt("secret", TENANT, RUN, "bank.csv");
        String[] fields = original.split(":", 4);
        byte[] bytes = Base64.getDecoder().decode(fields[3]);
        bytes[bytes.length - 1] ^= 1;
        String tampered = fields[0] + ":" + fields[1] + ":" + fields[2] + ":"
            + Base64.getEncoder().encodeToString(bytes);
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered, TENANT, RUN, "bank.csv"));
        assertThrows(IllegalStateException.class, () -> new BankExportCipher(key(3), "k1", "", "")
            .decrypt(original, TENANT, RUN, "bank.csv"));
    }

    @Test
    void rotatesUsingExplicitPreviousKeyOnly() {
        BankExportCipher old = new BankExportCipher(key(3), "old", "", "");
        String first = old.encrypt("CSV", TENANT, RUN, "bank.csv");
        BankExportCipher newer = new BankExportCipher(key(4), "current", key(3), "old");
        assertTrue(newer.needsRotation(first));
        assertEquals("CSV", newer.decrypt(first, TENANT, RUN, "bank.csv"));
        String updated = newer.encrypt("CSV", TENANT, RUN, "bank.csv");
        assertFalse(newer.needsRotation(updated));
        assertEquals("CSV", newer.decrypt(updated, TENANT, RUN, "bank.csv"));
        assertThrows(IllegalStateException.class, () -> new BankExportCipher(key(4), "current", "", "")
            .decrypt(first, TENANT, RUN, "bank.csv"));
    }

    @Test
    void deterministicDigestDoesNotExposeCsv() {
        String hash = BankExportCipher.sha256("ACCOUNT_NO,AMOUNT\n0123456789,100.00\n");
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[a-f0-9]{64}"));
        assertNotEquals(hash, BankExportCipher.sha256("ACCOUNT_NO,AMOUNT\n0123456789,100.01\n"));
    }

    @Test
    void refusesMissingKeysAndMalformedEnvelope() {
        BankExportCipher missing = new BankExportCipher("", "k1", "", "");
        assertThrows(IllegalStateException.class, () -> missing.encrypt("CSV", TENANT, RUN, "bank.csv"));
        assertThrows(IllegalStateException.class, () -> missing.decrypt("garbage", TENANT, RUN, "bank.csv"));
        assertThrows(IllegalStateException.class, () -> new BankExportCipher(key(2), "same", key(1), "same"));
    }
}
