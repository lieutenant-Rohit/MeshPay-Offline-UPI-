package com.offline.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offline.payment.model.MeshPacket;
import com.offline.payment.model.PaymentInstruction;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

public class TestRunner {

    public static void main(String[] args) {
        try {
            System.out.println("Starting Headless Client Offline UPI Simulation...");
            HttpClient client = HttpClient.newHttpClient();
            ObjectMapper mapper = new ObjectMapper();

            // 1. ALICE GENERATES Ed25519 + X25519 KEYS
            KeyPair ed25519Kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String alicePubKeyBase64 = Base64.getEncoder().encodeToString(ed25519Kp.getPublic().getEncoded());

            // 2. WI-FI ONBOARDING: Register with Bank & Get Bank's Keys
            System.out.println("[ONLINE] Provisioning Alice's device with the Bank...");
            Map<String, String> provisionReq = Map.of("vpa", "alice@upi", "publicKey", alicePubKeyBase64);

            HttpRequest setupRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8080/api/mesh/provision"))
                    .header("Content-Type", "application/json")
                    .header("X-Sender-Vpa", "alice@upi")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(provisionReq)))
                    .build();

            HttpResponse<String> setupResponse = client.send(setupRequest, HttpResponse.BodyHandlers.ofString());

            if (setupResponse.statusCode() != 200) {
                System.err.println("Provisioning Failed! HTTP " + setupResponse.statusCode());
                System.err.println("Response: " + setupResponse.body());
                return;
            }

            Map<?, ?> setupData = mapper.readValue(setupResponse.body(), Map.class);
            String bankEncryptPubB64 = (String) setupData.get("bankPublicKey");

            byte[] bankKeyBytes = Base64.getDecoder().decode(bankEncryptPubB64);
            PublicKey bankEncryptPub = KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(bankKeyBytes));
            System.out.println("Provisioning Complete. Acquired Bank X25519 Public Key.");

            // 3. ALICE WRITES AN OFFLINE TRANSACTION
            System.out.println("\n[OFFLINE] Alice initiating offline payment of 500.00 to Bob...");
            PaymentInstruction instruction = new PaymentInstruction(
                    "alice@upi", "bob@upi", new BigDecimal("500.00"),
                    "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918",
                    UUID.randomUUID().toString(), Instant.now().toEpochMilli()
            );

            byte[] plaintextBytes = mapper.writeValueAsBytes(instruction);

            // 4. HYBRID ENCRYPTION with X25519 ECDH + AES-GCM
            KeyPair ephemeralX25519 = KeyPairGenerator.getInstance("X25519").generateKeyPair();

            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(ephemeralX25519.getPrivate());
            ka.doPhase(bankEncryptPub, true);
            byte[] sharedSecret = ka.generateSecret();

            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update("upi-aes-key-derivation".getBytes());
            md.update(sharedSecret);
            md.update(iv);
            byte[] aesKey = md.digest();

            Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
            aesCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
            byte[] encryptedPayload = aesCipher.doFinal(plaintextBytes);

            String ciphertextBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(ephemeralX25519.getPublic().getEncoded()) + ":" +
                    Base64.getUrlEncoder().withoutPadding().encodeToString(iv) + ":" +
                    Base64.getUrlEncoder().withoutPadding().encodeToString(encryptedPayload);

            System.out.println("Payload encrypted with X25519 ECDH + AES-GCM.");

            // 5. Ed25519 DIGITAL SIGNATURE
            Signature sigEngine = Signature.getInstance("Ed25519");
            sigEngine.initSign(ed25519Kp.getPrivate());
            sigEngine.update(ciphertextBase64.getBytes());
            String signatureBase64 = Base64.getEncoder().encodeToString(sigEngine.sign());
            System.out.println("Ed25519 signature applied.");

            // 6. PACK THE ENVELOPE
            MeshPacket packet = new MeshPacket();
            packet.setPacketId(UUID.randomUUID().toString());
            packet.setTtl(5);
            packet.setCreatedAt(Instant.now().toEpochMilli());
            packet.setCiphertext(ciphertextBase64);
            packet.setSenderVpa("alice@upi");
            packet.setSignature(signatureBase64);

            // 7. TRANSMIT
            System.out.println("\n[ONLINE] Uploading transaction packet...");
            HttpRequest uploadRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8080/api/mesh/upload"))
                    .header("Content-Type", "application/json")
                    .header("X-Sender-Vpa", "alice@upi")
                    .header("X-Bridge-Node-Id", "phone-dave-gateway")
                    .header("X-Hop-Count", "2")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(packet)))
                    .build();

            HttpResponse<String> uploadResponse = client.send(uploadRequest, HttpResponse.BodyHandlers.ofString());

            System.out.println("\nServer Response! Code: " + uploadResponse.statusCode());
            System.out.println("Body: " + uploadResponse.body());
            System.out.println("\nSimulation completed successfully!");

        } catch (Exception e) {
            System.err.println("Critical Simulation Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
