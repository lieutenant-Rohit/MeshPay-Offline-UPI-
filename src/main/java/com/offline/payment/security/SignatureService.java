package com.offline.payment.security;

import org.springframework.stereotype.Service;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

@Service
public class SignatureService {

    public String signData(String data, PrivateKey privateKey) throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(privateKey);
        sig.update(data.getBytes());
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    public boolean verifySignature(String data, String signatureBase64, PublicKey publicKey) throws Exception {
        String clean = signatureBase64.replaceAll("[^A-Za-z0-9+/=]", "");
        Signature sig = Signature.getInstance("Ed25519");
        sig.initVerify(publicKey);
        sig.update(data.getBytes());
        return sig.verify(Base64.getDecoder().decode(clean));
    }
}
