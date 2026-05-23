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

    // 24 hours in milliseconds
    private static final long MAX_PACKET_AGE_MILLIS = 24L * 60 * 60 * 1000;

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
     * Master pipeline: clone defense → sender lookup → signature verify
     *                 → decrypt → freshness check → fund transfer → record
     */
    public void processIncomingPacket(MeshPacket packet) throws Exception {

        // STEP 1: Clone / replay defense (idempotency)
        String packetHash = generatePacketHash(packet.getCiphertext());
        if (transactionRepository.existsByPacketHash(packetHash)) {
            throw new SecurityException("Duplicate packet detected — this payment was already processed.");
        }

        // STEP 2: Fetch the sender's registered public key
        User sender = userRepository.findByVpa(packet.getSenderVpa())
                .orElseThrow(() -> new SecurityException(
                        "Sender VPA not found: " + packet.getSenderVpa()));

        // STEP 3: Verify the digital signature (proves the packet was created by the sender)
        boolean isValid = signatureService.verifySignature(
                packet.getCiphertext(),
                packet.getSignature(),
                sender.getPublicKey()
        );
        if (!isValid) {
            throw new SecurityException("Signature verification failed — dropping packet.");
        }

        // STEP 4: Decrypt the payload
        String plainTextJson = cryptoService.decryptPayload(packet.getCiphertext());
        PaymentInstruction instruction = parseJsonToInstruction(plainTextJson);

        // STEP 5: Authorization cross-check — outer VPA must match inner VPA
        if (!instruction.getSenderVpa().equals(sender.getVpa())) {
            throw new SecurityException("Authorization mismatch — envelope sender differs from payload sender.");
        }

        // STEP 6: Freshness check (zombie / replay packet defense)
        long packetAge = Instant.now().toEpochMilli() - instruction.getSignedAt();
        if (packetAge > MAX_PACKET_AGE_MILLIS) {
            throw new SecurityException("Packet expired — older than 24 hours.");
        }

        // STEP 7: Move the money
        ledgerService.transferFunds(
                instruction.getSenderVpa(),
                instruction.getReceiverVpa(),
                instruction.getAmount()
        );

        // STEP 8: Persist the permanent audit record
        saveTransactionRecord(packetHash, instruction, packet);
    }

    // --- Helper methods ---

    private PaymentInstruction parseJsonToInstruction(String json) throws Exception {
        return new ObjectMapper().readValue(json, PaymentInstruction.class);
    }

    private String generatePacketHash(String ciphertext) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(ciphertext.getBytes());
        return Base64.getEncoder().encodeToString(hashBytes);
    }

    private void saveTransactionRecord(String packetHash,
                                       PaymentInstruction instruction,
                                       MeshPacket packet) {
        Transaction tx = new Transaction();
        tx.setPacketHash(packetHash);
        tx.setSenderVpa(instruction.getSenderVpa());
        tx.setReceiverVpa(instruction.getReceiverVpa());
        tx.setAmount(instruction.getAmount());
        tx.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
        tx.setSettleAt(Instant.now());
        tx.setHopCount(5 - packet.getTtl());
        tx.setBridgeNodeId("Gateway-Node-ID");
        tx.setStatus(Transaction.Status.SETTLED);
        transactionRepository.save(tx);
    }
}