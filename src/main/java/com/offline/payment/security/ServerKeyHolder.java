package com.offline.payment.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.security.*;
import java.util.Base64;

@Component
public class ServerKeyHolder {

    private static final Logger log = LoggerFactory.getLogger(ServerKeyHolder.class);

    private KeyPair ed25519KeyPair;
    private KeyPair x25519KeyPair;

    private final ServerKeysRepository keysRepository;

    public ServerKeyHolder(ServerKeysRepository keysRepository) {
        this.keysRepository = keysRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void init() throws Exception {
        java.util.Optional<ServerKeys> existing = keysRepository.findById("default");

        if (existing.isPresent()) {
            loadKeys(existing.get());
            log.info("Bank Master Keys Loaded from Database!");
            return;
        }

        ed25519KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        x25519KeyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        ServerKeys sk = new ServerKeys();
        sk.setId("default");
        sk.setEd25519PrivateEncoded(Base64.getEncoder().encodeToString(ed25519KeyPair.getPrivate().getEncoded()));
        sk.setEd25519PublicEncoded(Base64.getEncoder().encodeToString(ed25519KeyPair.getPublic().getEncoded()));
        sk.setX25519PrivateEncoded(Base64.getEncoder().encodeToString(x25519KeyPair.getPrivate().getEncoded()));
        sk.setX25519PublicEncoded(Base64.getEncoder().encodeToString(x25519KeyPair.getPublic().getEncoded()));

        try {
            keysRepository.saveAndFlush(sk);
        } catch (DataIntegrityViolationException e) {
            // Another instance already inserted the shared keys — that's fine
        }

        ServerKeys stored = keysRepository.findById("default").orElseThrow();
        loadKeys(stored);
        log.info("Bank Master Ed25519 + X25519 KeyPairs Generated & Stored in DB!");
    }

    private void loadKeys(ServerKeys stored) throws Exception {
        KeyFactory ed25519kf = KeyFactory.getInstance("Ed25519");
        KeyFactory x25519kf = KeyFactory.getInstance("X25519");
        ed25519KeyPair = new KeyPair(
            ed25519kf.generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(stored.getEd25519PublicEncoded()))),
            ed25519kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(stored.getEd25519PrivateEncoded())))
        );
        x25519KeyPair = new KeyPair(
            x25519kf.generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(stored.getX25519PublicEncoded()))),
            x25519kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(stored.getX25519PrivateEncoded())))
        );
    }

    public PublicKey getSigningPublicKey() { return ed25519KeyPair.getPublic(); }
    public PrivateKey getSigningPrivateKey() { return ed25519KeyPair.getPrivate(); }
    public PublicKey getX25519PublicKey() { return x25519KeyPair.getPublic(); }
    public PrivateKey getX25519PrivateKey() { return x25519KeyPair.getPrivate(); }
    public String getSigningPublicKeyBase64() { return Base64.getEncoder().encodeToString(ed25519KeyPair.getPublic().getEncoded()); }
    public String getEncryptionPublicKeyBase64() { return Base64.getEncoder().encodeToString(x25519KeyPair.getPublic().getEncoded()); }
}
