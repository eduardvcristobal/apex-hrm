package com.apex.hrm.personnel;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankAccountCipherTest {
    private static String key(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return Base64.getEncoder().encodeToString(bytes);
    }
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID EMPLOYEE = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void encryptedValuesHaveUniqueAuthenticatedEnvelopes() {
        BankAccountCipher cipher = new BankAccountCipher(key(1), "k1", "", "");
        String a = cipher.encrypt("123456789012", TENANT, EMPLOYEE);
        String b = cipher.encrypt("123456789012", TENANT, EMPLOYEE);
        assertNotEquals(a, b);
        assertTrue(a.startsWith("b1:k1:"));
        assertFalse(a.contains("123456789012"));
        assertEquals("123456789012", cipher.decrypt(a, TENANT, EMPLOYEE));
        assertEquals("123456789012", cipher.decrypt(b, TENANT, EMPLOYEE));
    }

    @Test
    void rejectsTenantOrEmployeeCiphertextSubstitutionAndTampering() {
        BankAccountCipher cipher = new BankAccountCipher(key(2), "k1", "", "");
        String encrypted = cipher.encrypt("9876543210", TENANT, EMPLOYEE);
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(encrypted, UUID.randomUUID(), EMPLOYEE));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(encrypted, TENANT, UUID.randomUUID()));
        String[] parts = encrypted.split(":", 4);
        byte[] bytes = Base64.getDecoder().decode(parts[3]);
        bytes[bytes.length - 1] ^= 1;
        String corrupted = parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + Base64.getEncoder().encodeToString(bytes);
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(corrupted, TENANT, EMPLOYEE));
    }

    @Test
    void supportsPreviousKeyOnlyDuringExplicitRotationWindow() {
        BankAccountCipher old = new BankAccountCipher(key(3), "old", "", "");
        String encrypted = old.encrypt("5555550000", TENANT, EMPLOYEE);
        BankAccountCipher rotated = new BankAccountCipher(key(4), "new", key(3), "old");
        assertTrue(rotated.needsRotation(encrypted));
        assertEquals("5555550000", rotated.decrypt(encrypted, TENANT, EMPLOYEE));
        String newEnvelope = rotated.encrypt("5555550000", TENANT, EMPLOYEE);
        assertFalse(rotated.needsRotation(newEnvelope));
        assertTrue(newEnvelope.startsWith("b1:new:"));
        assertThrows(IllegalStateException.class, () ->
            new BankAccountCipher(key(4), "new", "", "").decrypt(encrypted, TENANT, EMPLOYEE));
    }

    @Test
    void failsClosedWithMissingOrInvalidKeys() {
        BankAccountCipher none = new BankAccountCipher("", "k1", "", "");
        assertThrows(IllegalStateException.class, () -> none.encrypt("123", TENANT, EMPLOYEE));
        assertThrows(IllegalStateException.class, () -> none.decrypt("b1:k1:test:test", TENANT, EMPLOYEE));
        assertThrows(IllegalStateException.class, () ->
            new BankAccountCipher(Base64.getEncoder().encodeToString("short".getBytes(StandardCharsets.UTF_8)),
                "k1", "", ""));
        assertThrows(IllegalStateException.class, () ->
            new BankAccountCipher(key(1), "same", key(2), "same"));
    }

    @Test
    void neverExposeUnmaskedShortAccounts() {
        assertEquals("****", BankAccountService.mask("1234"));
        assertEquals("****7890", BankAccountService.mask("1234567890"));
    }
}
