package com.apex.hrm.platform.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MfaSecretCipherSecurityTest {
    private static String key(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test void encryptsAtRestAndUsesUniqueAuthenticatedEnvelopes() {
        MfaSecretCipher cipher = new MfaSecretCipher(key(1));
        String secret = "JBSWY3DPEHPK3PXP";
        String first = cipher.encrypt(secret);
        String second = cipher.encrypt(secret);
        assertTrue(first.startsWith("v2:"));
        assertNotEquals(first, second, "AES-GCM IV must be randomized");
        assertFalse(first.contains(secret));
        assertEquals(secret, cipher.decrypt(first));
        assertEquals(secret, cipher.decrypt(second));
    }

    @Test void rejectsCiphertextTamperingAndWrongKey() {
        MfaSecretCipher cipher = new MfaSecretCipher(key(2));
        String envelope = cipher.encrypt("TOP-SECRET");
        String[] parts = envelope.split(":", 3);
        byte[] data = Base64.getDecoder().decode(parts[2]);
        data[data.length - 1] ^= 1;
        String tampered = parts[0] + ":" + parts[1] + ":" + Base64.getEncoder().encodeToString(data);
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
        assertThrows(IllegalStateException.class, () -> new MfaSecretCipher(key(3)).decrypt(envelope));
    }

    @Test void failsClosedWhenKeyIsMissingOrInvalid() {
        assertThrows(IllegalStateException.class, () -> new MfaSecretCipher("").encrypt("secret"));
        assertThrows(IllegalStateException.class, () -> new MfaSecretCipher("").decrypt("v2:bad:bad"));
        assertThrows(IllegalStateException.class, () -> new MfaSecretCipher(Base64.getEncoder()
            .encodeToString("short".getBytes(StandardCharsets.UTF_8))));
    }

    @Test void previousKeyWorksOnlyWhenExplicitlyProvided() {
        MfaSecretCipher earlier = new MfaSecretCipher(key(4));
        String payload = earlier.encrypt("rotated");
        assertEquals("rotated", new MfaSecretCipher(key(5), key(4)).decrypt(payload));
        assertThrows(IllegalStateException.class, () -> new MfaSecretCipher(key(5)).decrypt(payload));
    }
}
