package com.apex.hrm.personnel;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Versioned AES-256-GCM bank account encryption, bound to tenant + employee.
 * Keys must be provisioned outside Git and are intentionally separate from MFA/other PII.
 */
@Component
public final class BankAccountCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private final SecretKeySpec current;
    private final SecretKeySpec previous;
    private final String currentId;
    private final String previousId;

    public BankAccountCipher(@Value("${HRM_BANK_ACCOUNT_KEY:}") String key,
                             @Value("${HRM_BANK_ACCOUNT_KEY_ID:k1}") String keyId,
                             @Value("${HRM_BANK_ACCOUNT_PREVIOUS_KEY:}") String oldKey,
                             @Value("${HRM_BANK_ACCOUNT_PREVIOUS_KEY_ID:}") String oldKeyId) {
        if (!KEY_ID.matcher(keyId).matches()) throw new IllegalStateException("Invalid bank key identifier");
        if (!oldKey.isBlank() && (!KEY_ID.matcher(oldKeyId).matches() || keyId.equals(oldKeyId))) {
            throw new IllegalStateException("Previous bank key must have a distinct identifier");
        }
        this.current = decodeKey(key);
        this.previous = decodeKey(oldKey);
        this.currentId = keyId;
        this.previousId = oldKeyId;
    }

    private static SecretKeySpec decodeKey(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        byte[] raw;
        try { raw = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException ex) { throw new IllegalStateException("Invalid bank encryption key", ex); }
        if (raw.length != 32) throw new IllegalStateException("Bank encryption key must be 32 bytes");
        return new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String value, UUID tenant, UUID employee) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Account number required");
        if (current == null) throw new IllegalStateException("HRM_BANK_ACCOUNT_KEY must be configured");
        if (tenant == null || employee == null) throw new IllegalArgumentException("Tenant and employee required");
        try {
            byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, current, new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(tenant, employee));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "b1:" + currentId + ":" + Base64.getEncoder().encodeToString(iv)
                + ":" + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception ex) { throw new IllegalStateException("Bank account encryption failed", ex); }
    }

    public String decrypt(String envelope, UUID tenant, UUID employee) {
        if (envelope == null || tenant == null || employee == null)
            throw new IllegalStateException("Missing encrypted account or account context");
        try {
            String[] parts = envelope.split(":", 4);
            if (parts.length != 4 || !"b1".equals(parts[0])) throw new IllegalArgumentException("Invalid bank envelope");
            SecretKeySpec key;
            if (parts[1].equals(currentId)) key = current;
            else if (parts[1].equals(previousId)) key = previous;
            else throw new IllegalStateException("Unknown bank account key identifier");
            if (key == null) throw new IllegalStateException("Required bank account decryption key unavailable");
            byte[] iv = Base64.getDecoder().decode(parts[2]);
            if (iv.length != 12) throw new IllegalArgumentException("Invalid bank account nonce");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(tenant, employee));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[3])), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new IllegalStateException("Bank account decryption failed", ex); }
    }

    public boolean needsRotation(String envelope) {
        return envelope != null && !envelope.startsWith("b1:" + currentId + ":");
    }

    private static byte[] aad(UUID tenant, UUID employee) {
        return ("apex-hrm:bank-account:" + tenant + ":" + employee).getBytes(StandardCharsets.UTF_8);
    }
}
