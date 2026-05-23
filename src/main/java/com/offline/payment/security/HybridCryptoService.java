package com.offline.payment.security;

import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;

@Service
public class HybridCryptoService {

    private final ServerKeyHolder serverKeyHolder;

    public HybridCryptoService(ServerKeyHolder serverKeyHolder) {
        this.serverKeyHolder = serverKeyHolder;
    }

    public String encryptPayload(String plainTextJson) throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey aesKey = keyGen.generateKey();
        byte[] iv = new byte[12];
        SecureRandom.getInstanceStrong().nextBytes(iv);
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, spec);
        byte[] encryptedData = aesCipher.doFinal(plainTextJson.getBytes());
        OAEPParameterSpec oaepSpec = new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsaCipher.init(Cipher.ENCRYPT_MODE, serverKeyHolder.getPublicKey(), oaepSpec);
        byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(iv) + ":" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(encryptedAesKey) + ":" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(encryptedData);
    }

    public String decryptPayload(String ciphertext) throws Exception {
        String[] parts = ciphertext.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid ciphertext: expected 3 parts, got " + parts.length);
        }

        Base64.Decoder dec = Base64.getUrlDecoder();
        byte[] iv              = dec.decode(padBase64(parts[0]));
        byte[] encryptedAesKey = dec.decode(padBase64(parts[1]));
        byte[] encryptedData   = dec.decode(padBase64(parts[2]));

        OAEPParameterSpec oaepSpec = new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsaCipher.init(Cipher.DECRYPT_MODE, serverKeyHolder.getPrivateKey(), oaepSpec);
        byte[] aesKeyBytes = rsaCipher.doFinal(encryptedAesKey);

        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        aesCipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKeyBytes, "AES"), new GCMParameterSpec(128, iv));
        return new String(aesCipher.doFinal(encryptedData));
    }

    // Adds the missing '=' padding that getUrlDecoder strictly requires
    private String padBase64(String s) {
        int mod = s.length() % 4;
        if (mod == 2) return s + "==";
        if (mod == 3) return s + "=";
        return s;
    }
}