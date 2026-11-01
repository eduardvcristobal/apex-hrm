package com.apex.hrm.payroll.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Dedicated authenticated encryption for payroll CSV files, independent of bank-account keys. */
@Component
public final class BankExportCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private final String currentId;
    private final String previousId;
    private final SecretKeySpec current;
    private final SecretKeySpec previous;

    public BankExportCipher(@Value("${HRM_BANK_EXPORT_KEY:}") String key,
                            @Value("${HRM_BANK_EXPORT_KEY_ID:k1}") String keyId,
                            @Value("${HRM_BANK_EXPORT_PREVIOUS_KEY:}") String oldKey,
                            @Value("${HRM_BANK_EXPORT_PREVIOUS_KEY_ID:}") String oldKeyId) {
        if (!ID.matcher(keyId).matches()) throw new IllegalStateException("Invalid export encryption key ID");
        if (oldKey != null && !oldKey.isBlank()
                && (!ID.matcher(oldKeyId).matches() || keyId.equals(oldKeyId)))
            throw new IllegalStateException("Previous export key requires a distinct ID");
        currentId = keyId;
        previousId = oldKeyId;
        current = key(key);
        previous = key(oldKey);
    }

    private static SecretKeySpec key(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length != 32) throw new IllegalStateException("Bank export key must be 32 bytes");
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid bank export key", ex);
        }
    }

    public boolean hasCurrentKey() { return current != null; }
    public boolean needsRotation(String envelope) {
        return envelope != null && !envelope.startsWith("e1:" + currentId + ":");
    }

    public String encrypt(String plaintext, UUID tenant, UUID run, String filename) {
        if (current == null) throw new IllegalStateException("HRM_BANK_EXPORT_KEY is required");
        if (plaintext == null || tenant == null || run == null || filename == null)
            throw new IllegalArgumentException("Missing export or authenticated context");
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, current, new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(tenant, run, filename));
            return "e1:" + currentId + ":" + Base64.getEncoder().encodeToString(iv) + ":"
                + Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException("Bank export encryption failed", ex); }
    }

    public String decrypt(String envelope, UUID tenant, UUID run, String filename) {
        if (envelope == null || tenant == null || run == null || filename == null)
            throw new IllegalStateException("Bank export or decryption context missing");
        try {
            String[] parts = envelope.split(":", 4);
            if (parts.length != 4 || !"e1".equals(parts[0])) throw new IllegalArgumentException("Invalid export envelope");
            SecretKeySpec key;
            if (parts[1].equals(currentId)) key = current;
            else if (parts[1].equals(previousId)) key = previous;
            else throw new IllegalStateException("Unknown export encryption key ID");
            if (key == null) throw new IllegalStateException("Bank export key unavailable");
            byte[] iv = Base64.getDecoder().decode(parts[2]);
            if (iv.length != 12) throw new IllegalArgumentException("Invalid export nonce");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(tenant, run, filename));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[3])), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new IllegalStateException("Bank export decryption failed", ex); }
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception ex) { throw new IllegalStateException("Cannot hash bank export", ex); }
    }

    private static byte[] aad(UUID tenant, UUID run, String filename) {
        return ("apex-hrm:bank-export:v1:" + tenant + ":" + run + ":" + filename)
            .getBytes(StandardCharsets.UTF_8);
    }
}
