package com.apex.hrm.payroll.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FinalPayDocumentVerificationServiceTest {
    @Test void pinnedEd25519ProofRejectsDifferentDocumentTenantOrIssuer() throws Exception{
        var keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey=Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        UUID tenant=UUID.randomUUID(),attestation=UUID.randomUUID();
        String docHash="f".repeat(64);
        String signed=FinalPayDocumentVerificationService.claim(tenant,attestation,docHash,"BANK_KEY_1");
        Signature signer=Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(signed.getBytes(StandardCharsets.UTF_8));
        String sig=Base64.getEncoder().encodeToString(signer.sign());
        assertTrue(FinalPayDocumentVerificationService.verifySignature(publicKey,signed,sig));
        assertFalse(FinalPayDocumentVerificationService.verifySignature(publicKey,
            FinalPayDocumentVerificationService.claim(UUID.randomUUID(),attestation,docHash,"BANK_KEY_1"),sig));
        assertFalse(FinalPayDocumentVerificationService.verifySignature(publicKey,
            FinalPayDocumentVerificationService.claim(tenant,attestation,"e".repeat(64),"BANK_KEY_1"),sig));
        assertFalse(FinalPayDocumentVerificationService.verifySignature(publicKey,
            FinalPayDocumentVerificationService.claim(tenant,attestation,docHash,"OTHER_KEY"),sig));
        assertFalse(FinalPayDocumentVerificationService.verifySignature(publicKey,signed,"broken"));
    }
    @Test void allPriorAttestorsAreExcludedFromSignatureVerification(){
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID(),d=UUID.randomUUID();
        for(UUID actor:new UUID[]{a,b,c,d})
            assertEquals(403,assertThrows(ResponseStatusException.class,
                ()->FinalPayDocumentVerificationService.requireIndependentVerification(actor,a,b,c,d))
                .getStatusCode().value());
        assertDoesNotThrow(()->FinalPayDocumentVerificationService.requireIndependentVerification(
            UUID.randomUUID(),a,b,c,d));
    }
}
