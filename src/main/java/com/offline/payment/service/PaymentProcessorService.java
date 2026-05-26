package com.offline.payment.service;

import com.offline.payment.config.CacheService;
import com.offline.payment.model.MeshPacket;
import com.offline.payment.model.PaymentInstruction;
import com.offline.payment.model.Transaction;
import com.offline.payment.model.User;
import com.offline.payment.security.HybridCryptoService;
import com.offline.payment.security.SignatureService;
import com.offline.payment.repository.UserRepository;
import com.offline.payment.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;

@Service
public class PaymentProcessorService {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessorService.class);

    private final SignatureService signatureService;
    private final HybridCryptoService cryptoService;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerService ledgerService;
    private final CacheService cacheService;
    private final OutboxService outboxService;

    private static final long MAX_PACKET_AGE_MILLIS = 24L * 60 * 60 * 1000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public PaymentProcessorService(SignatureService signatureService,
                                   HybridCryptoService cryptoService,
                                   UserRepository userRepository,
                                   TransactionRepository transactionRepository,
                                   LedgerService ledgerService,
                                   CacheService cacheService,
                                   OutboxService outboxService) {
        this.signatureService = signatureService;
        this.cryptoService = cryptoService;
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerService = ledgerService;
        this.cacheService = cacheService;
        this.outboxService = outboxService;
    }

    public void processIncomingPacket(MeshPacket packet) throws Exception {
        String packetHash = generatePacketHash(packet.getCiphertext());

        // STEP 1: Clone / replay defense (cache-first, then DB)
        if (cacheService.isPacketHashProcessed(packetHash)) {
            throw new SecurityException("Duplicate packet detected — this payment was already processed.");
        }
        if (transactionRepository.existsByPacketHash(packetHash)) {
            cacheService.markPacketHash(packetHash);
            throw new SecurityException("Duplicate packet detected — this payment was already processed.");
        }

        // STEP 2: Fetch sender's public key (cache-first, then DB)
        User sender = getSenderUser(packet.getSenderVpa());
        PublicKey senderPublicKey = sender.getPublicKey();

        // STEP 3: Verify signature
        boolean isValid = signatureService.verifySignature(
                packet.getCiphertext(),
                packet.getSignature(),
                senderPublicKey
        );
        if (!isValid) {
            throw new SecurityException("Signature verification failed — dropping packet.");
        }

        // STEP 4: Decrypt payload
        String plainTextJson = cryptoService.decryptPayload(packet.getCiphertext());
        PaymentInstruction instruction = parseJsonToInstruction(plainTextJson);

        // STEP 5: Authorization cross-check
        if (!instruction.getSenderVpa().equals(sender.getVpa())) {
            throw new SecurityException("Authorization mismatch — envelope sender differs from payload sender.");
        }

        // STEP 6: Freshness check
        long packetAge = Instant.now().toEpochMilli() - instruction.getSignedAt();
        if (packetAge > MAX_PACKET_AGE_MILLIS) {
            throw new SecurityException("Packet expired — older than 24 hours.");
        }

        // STEP 7: Move the money (atomic debit/credit via native SQL)
        ledgerService.transferFunds(
                instruction.getSenderVpa(),
                instruction.getReceiverVpa(),
                instruction.getAmount()
        );

        // STEP 8: Persist audit record and outbox event
        saveTransactionRecord(packetHash, instruction, packet);
        outboxService.publishEvent(packetHash, instruction.getSenderVpa(),
                instruction.getReceiverVpa(), instruction.getAmount());

        // STEP 9: Mark hash in cache to prevent replay
        cacheService.markPacketHash(packetHash);
    }

    private User getSenderUser(String senderVpa) throws Exception {
        String cachedKey = cacheService.getCachedUser(senderVpa);
        if (cachedKey != null) {
            User cached = new User();
            cached.setVpa(senderVpa);
            cached.setPublicKeyBase64(cachedKey);
            return cached;
        }
        User sender = userRepository.findByVpa(senderVpa)
                .orElseThrow(() -> new SecurityException("Sender VPA not found: " + senderVpa));
        cacheService.cacheUser(sender.getVpa(), sender.getPublicKeyBase64());
        return sender;
    }

    private PaymentInstruction parseJsonToInstruction(String json) throws Exception {
        return objectMapper.readValue(json, PaymentInstruction.class);
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