package com.offline.payment.security;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Service
public class HybridCryptoService {

    private final ServerKeyHolder serverKeyHolder;

    public HybridCryptoService(ServerKeyHolder serverKeyHolder) {
        this.serverKeyHolder = serverKeyHolder;
    }

    public String encryptPayload(String plainTextJson) throws Exception {
        KeyPair ephemeral = KeyPairGenerator.getInstance("X25519").generateKeyPair();

        KeyAgreement ka = KeyAgreement.getInstance("X25519");
        ka.init(ephemeral.getPrivate());
        ka.doPhase(serverKeyHolder.getX25519PublicKey(), true);
        byte[] sharedSecret = ka.generateSecret();

        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        byte[] aesKey = deriveKey(sharedSecret, iv);

        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        aesCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
        byte[] encrypted = aesCipher.doFinal(plainTextJson.getBytes());

        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString(ephemeral.getPublic().getEncoded()) + ":" +
               enc.encodeToString(iv) + ":" +
               enc.encodeToString(encrypted);
    }

    public String decryptPayload(String ciphertext) throws Exception {
        String[] parts = ciphertext.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid ciphertext: expected 3 parts, got " + parts.length);
        }

        Base64.Decoder dec = Base64.getUrlDecoder();
        byte[] ephemeralPubEncoded = dec.decode(padBase64(parts[0]));
        byte[] iv                  = dec.decode(padBase64(parts[1]));
        byte[] encryptedData       = dec.decode(padBase64(parts[2]));

        KeyFactory kf = KeyFactory.getInstance("X25519");
        PublicKey ephemeralPub = kf.generatePublic(new X509EncodedKeySpec(ephemeralPubEncoded));

        KeyAgreement ka = KeyAgreement.getInstance("X25519");
        ka.init(serverKeyHolder.getX25519PrivateKey());
        ka.doPhase(ephemeralPub, true);
        byte[] sharedSecret = ka.generateSecret();

        byte[] aesKey = deriveKey(sharedSecret, iv);

        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        aesCipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
        return new String(aesCipher.doFinal(encryptedData));
    }

    private byte[] deriveKey(byte[] sharedSecret, byte[] iv) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update("upi-aes-key-derivation".getBytes());
            md.update(sharedSecret);
            md.update(iv);
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String padBase64(String s) {
        int mod = s.length() % 4;
        if (mod == 2) return s + "==";
        if (mod == 3) return s + "=";
        return s;
    }
}
