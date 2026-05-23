package com.offline.payment.security;


import org.springframework.stereotype.Service;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

//Handles Digital Signature to guarantee Authentication and Non-Repudiation
@Service
public class SignatureService {
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    /**
     * Called By the phone(offline)
     * Create a mathematical proof that the user created this packet
     */

    public String signData(String cipherText, PrivateKey userPrivateKey)throws Exception
    {
        //1-> Build Signature Machine
        Signature signatureMachine = Signature.getInstance(SIGNATURE_ALGORITHM);

        //2-> Insert User's Private ket and set to "Sign" mode
        signatureMachine.initSign(userPrivateKey);

        //3-> Feed the locked safe (ciphertext) into the machine
        signatureMachine.update(cipherText.getBytes());

        //4-> Start to generate the rae signature bytes
        byte[] digitalSignature = signatureMachine.sign();

        //5-> Convert to Base64 so t can travel safely over bluetooth
        return Base64.getEncoder().encodeToString(digitalSignature);
    }

    public boolean verifySignature(String cipherText, String signatureBase64, PublicKey userPublicKey) throws Exception {
        // DEFENSIVE FIX: Remove all characters that are not in the Base64 alphabet.
        // This strips out the '.' (2e) character and any other transit artifacts.
        String cleanSignature = signatureBase64.replaceAll("[^A-Za-z0-9+/=]", "");

        Signature signatureMachine = Signature.getInstance(SIGNATURE_ALGORITHM);
        signatureMachine.initVerify(userPublicKey);
        signatureMachine.update(cipherText.getBytes());

        // Use the cleaned string
        byte[] signatureBytes = Base64.getDecoder().decode(cleanSignature);

        return signatureMachine.verify(signatureBytes);
    }
}
