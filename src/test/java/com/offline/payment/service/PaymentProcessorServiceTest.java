package com.offline.payment.service;

import com.offline.payment.model.MeshPacket;
import com.offline.payment.model.Transaction;
import com.offline.payment.model.User;
import com.offline.payment.repository.TransactionRepository;
import com.offline.payment.repository.UserRepository;
import com.offline.payment.security.HybridCryptoService;
import com.offline.payment.security.SignatureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PaymentProcessorServiceTest {

    @Mock
    private SignatureService signatureService;

    @Mock
    private HybridCryptoService cryptoService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private LedgerService ledgerService;

    @InjectMocks
    private PaymentProcessorService paymentProcessorService;

    private MeshPacket incomingPacket;
    private User senderAlice;
    private String validPayloadJson;

    @BeforeEach
    public void setUp() throws Exception {
        // 1. Setup the incoming fake packet
        incomingPacket = new MeshPacket();
        incomingPacket.setSenderVpa("alice@bank");
        incomingPacket.setSignature("VALID_SIGNATURE");
        incomingPacket.setCiphertext("ENCRYPTED_SAFE_DATA");
        incomingPacket.setTtl(5);

        // 2. Mock the User to perfectly bypass the missing setter errors
        senderAlice = mock(User.class);
        lenient().when(senderAlice.getVpa()).thenReturn("alice@bank");
        lenient().when(senderAlice.getPublicKey()).thenReturn(mock(PublicKey.class));

        // 3. Setup the decrypted JSON that the CryptoService will reveal
        long recentTimestamp = Instant.now().toEpochMilli() - 10000; // 10 seconds ago
        validPayloadJson = "{" +
                "\"senderVpa\":\"alice@bank\"," +
                "\"receiverVpa\":\"bob@bank\"," +
                "\"amount\":500.00," +
                "\"signedAt\":" + recentTimestamp +
                "}";
    }

    @Test
    @DisplayName("Should process valid packet, transfer funds, and save transaction")
    public void testProcessPacket_Success() throws Exception {
        // Arrange
        when(transactionRepository.existsByPacketHash(anyString())).thenReturn(false);
        when(userRepository.findByVpa("alice@bank")).thenReturn(Optional.of(senderAlice));

        // Pass any PublicKey object to match your actual method signature
        when(signatureService.verifySignature(anyString(), anyString(), any(PublicKey.class))).thenReturn(true);
        when(cryptoService.decryptPayload("ENCRYPTED_SAFE_DATA")).thenReturn(validPayloadJson);

        // Act
        paymentProcessorService.processIncomingPacket(incomingPacket);

        // Assert: Verify money was actually moved using BigDecimal!
        verify(ledgerService, times(1)).transferFunds(eq("alice@bank"), eq("bob@bank"), any(BigDecimal.class));

        // Assert: Capture the saved transaction to ensure it was logged correctly
        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(1)).save(txCaptor.capture());

        Transaction savedTx = txCaptor.getValue();
        assertEquals("alice@bank", savedTx.getSenderVpa());
        assertEquals(Transaction.Status.SETTLED, savedTx.getStatus());
    }

    @Test
    @DisplayName("Should block Replay Attacks (Duplicate Packets)")
    public void testProcessPacket_ReplayAttack_ThrowsException() {
        when(transactionRepository.existsByPacketHash(anyString())).thenReturn(true);

        SecurityException exception = assertThrows(SecurityException.class, () ->
                paymentProcessorService.processIncomingPacket(incomingPacket)
        );

        assertTrue(exception.getMessage().contains("Duplicate Packet Detected"));
        verify(ledgerService, never()).transferFunds(anyString(), anyString(), any(BigDecimal.class));
    }

    @Test
    @DisplayName("Should drop packet if Digital Signature is invalid")
    public void testProcessPacket_InvalidSignature_ThrowsException() throws Exception {
        when(transactionRepository.existsByPacketHash(anyString())).thenReturn(false);
        when(userRepository.findByVpa("alice@bank")).thenReturn(Optional.of(senderAlice));

        // Simulate a hacker altering the data, causing the signature check to fail
        when(signatureService.verifySignature(anyString(), anyString(), any(PublicKey.class))).thenReturn(false);

        SecurityException exception = assertThrows(SecurityException.class, () ->
                paymentProcessorService.processIncomingPacket(incomingPacket)
        );

        assertTrue(exception.getMessage().contains("Signature Verification Failed"));
        verify(cryptoService, never()).decryptPayload(anyString());
    }

    @Test
    @DisplayName("Should block Zombie Packets (Older than 24 Hours)")
    public void testProcessPacket_ExpiredPacket_ThrowsException() throws Exception {
        long oldTimestamp = Instant.now().toEpochMilli() - (48 * 60 * 60 * 1000);
        String expiredPayloadJson = "{" +
                "\"senderVpa\":\"alice@bank\"," +
                "\"receiverVpa\":\"bob@bank\"," +
                "\"amount\":500.00," +
                "\"signedAt\":" + oldTimestamp +
                "}";

        when(transactionRepository.existsByPacketHash(anyString())).thenReturn(false);
        when(userRepository.findByVpa("alice@bank")).thenReturn(Optional.of(senderAlice));
        when(signatureService.verifySignature(anyString(), anyString(), any(PublicKey.class))).thenReturn(true);
        when(cryptoService.decryptPayload("ENCRYPTED_SAFE_DATA")).thenReturn(expiredPayloadJson);

        SecurityException exception = assertThrows(SecurityException.class, () ->
                paymentProcessorService.processIncomingPacket(incomingPacket)
        );

        assertTrue(exception.getMessage().contains("Packet Expired"));
        verify(ledgerService, never()).transferFunds(anyString(), anyString(), any(BigDecimal.class));
    }
}