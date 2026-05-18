package com.offline.payment.service;

import com.offline.payment.model.MeshPacket;
import com.offline.payment.model.PaymentInstruction;
import com.offline.payment.model.Transaction;
import com.offline.payment.model.User;
import com.offline.payment.security.HybridCryptoService;
import com.offline.payment.security.SignatureService;
import com.offline.payment.repository.UserRepository;
import com.offline.payment.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

@Service
public class PaymentProcessorService {

    private final SignatureService signatureService;
    private final HybridCryptoService cryptoService;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerService ledgerService;

    // 24 hours in milliseconds (The maximum time a packet can bounce in the mesh)
    private static final long MAX_PACKET_AGE_MILLIS = 24 * 60 * 60 * 1000;

    public PaymentProcessorService(SignatureService signatureService,
                                   HybridCryptoService cryptoService,
                                   UserRepository userRepository,
                                   TransactionRepository transactionRepository,
                                   LedgerService ledgerService) {
        this.signatureService = signatureService;
        this.cryptoService = cryptoService;
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerService = ledgerService;
    }

    /**
     * 🏦 The Master Method: Processes the incoming Mesh Packet
     */
    public void processIncomingPacket(MeshPacket packet) throws Exception {

        // STEP 1: The Clone Defense (Idempotency)
        // Generate a unique fingerprint (Hash) of the encrypted safe
        String packetHash = generatePacketHash(packet.getCiphertext());

        if (transactionRepository.existsByPacketHash(packetHash)) {
            throw new SecurityException("Duplicate Packet Detected! This payment was already processed.");
        }

        // STEP 2: Fetch the Sender
        User sender = userRepository.findByVpa(packet.getSenderVpa())
                .orElseThrow(() -> new SecurityException("User Not Found in Database!"));

        // STEP 3: Verify the Signature (The Wax Seal)
        boolean isValid = signatureService.verifySignature(
                packet.getCiphertext(),
                packet.getSignature(),
                sender.getPublicKey()
        );

        if (!isValid) {
            throw new SecurityException("Signature Verification Failed! Dropping packet.");
        }

        // STEP 4: Decrypt the Safe
        String plainTextJson = cryptoService.decryptPayload(packet.getCiphertext());
        PaymentInstruction instruction = parseJsonToInstruction(plainTextJson);

        // STEP 5: Final Authorization Checks
        if (!instruction.getSenderVpa().equals(sender.getVpa())) {
            throw new SecurityException("Authorization mismatch! Packet sender does not match payload sender.");
        }

        // STEP 6: The Zombie Defense (Freshness Check)
        long currentTime = Instant.now().toEpochMilli();
        long packetAge = currentTime - instruction.getSignedAt();

        if (packetAge > MAX_PACKET_AGE_MILLIS) {
            throw new SecurityException("Packet Expired! This payment is older than 24 hours.");
        }

        // STEP 7: Move the Money!
        ledgerService.transferFunds(
                instruction.getSenderVpa(),
                instruction.getReceiverVpa(),
                instruction.getAmount()
        );

        // STEP 8: Write the Permanent Record
        saveTransactionRecord(packetHash, instruction, packet);
    }

    // --- Helper Methods ---

    private PaymentInstruction parseJsonToInstruction(String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(json, PaymentInstruction.class);
    }

    private String generatePacketHash(String ciphertext) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(ciphertext.getBytes());
        return Base64.getEncoder().encodeToString(hashBytes);
    }

    private void saveTransactionRecord(String packetHash, PaymentInstruction instruction, MeshPacket packet) {
        Transaction tx = new Transaction();
        tx.setPacketHash(packetHash);
        tx.setSenderVpa(instruction.getSenderVpa());
        tx.setReceiverVpa(instruction.getReceiverVpa());
        tx.setAmount(instruction.getAmount());
        tx.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
        tx.setSettleAt(Instant.now());
        tx.setHopCount(5 - packet.getTtl()); // Calculate how many jumps it took
        tx.setBridgeNodeId("Gateway-Node-ID"); // We will pass the real Dave's ID here later
        tx.setStatus(Transaction.Status.SETTLED);

        transactionRepository.save(tx);
    }
}