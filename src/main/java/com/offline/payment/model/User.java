package com.offline.payment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Entity
@Table(name = "users")
public class User {

    @Id
    private String vpa;

    @Column(nullable = false, length = 1024)
    private String publicKeyBase64;

    public User() {}

    public String getVpa() { return vpa; }
    public void setVpa(String vpa) { this.vpa = vpa; }

    public String getPublicKeyBase64() { return publicKeyBase64; }
    public void setPublicKeyBase64(String publicKeyBase64) { this.publicKeyBase64 = publicKeyBase64; }

    public PublicKey getPublicKey() throws Exception {
        if (publicKeyBase64 == null || publicKeyBase64.isEmpty()) {
            throw new IllegalStateException("User does not have a public key registered!");
        }
        byte[] keyBytes = Base64.getDecoder().decode(this.publicKeyBase64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(spec);
        } catch (Exception e) {
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        }
    }
}
