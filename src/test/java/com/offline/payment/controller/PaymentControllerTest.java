package com.offline.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offline.payment.model.MeshPacket;
import com.offline.payment.service.PaymentProcessorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
public class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PaymentProcessorService paymentProcessorService;

    private MeshPacket validPacket;

    // Added 'public' here!
    @BeforeEach
    public void setUp() {
        validPacket = new MeshPacket();
        validPacket.setPacketId("PKT_123456_ABC");
        validPacket.setTtl(5);
        validPacket.setCreatedAt(System.currentTimeMillis());
        validPacket.setSenderVpa("alice@bank");
        validPacket.setSignature("DIGITAL_WAX_SEAL_HASH");
        validPacket.setCiphertext("LOCKED_SAFE_PAYLOAD");
    }

    @Test
    @DisplayName("Should return 200 OK when Bank successfully processes a valid packet")
    public void testUploadPacket_Success() throws Exception { // Added 'public' here!
        doNothing().when(paymentProcessorService).processIncomingPacket(any(MeshPacket.class));

        mockMvc.perform(post("/api/mesh/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPacket)))
                .andExpect(status().isOk())
                .andExpect(content().string("Transaction Processed and Settled Successfully!"));
    }

    @Test
    @DisplayName("Should return 403 Forbidden when Cryptographic Signature is invalid")
    public void testUploadPacket_SecurityViolation() throws Exception { // Added 'public' here!
        doThrow(new SecurityException("Invalid digital wax seal!"))
                .when(paymentProcessorService).processIncomingPacket(any(MeshPacket.class));

        mockMvc.perform(post("/api/mesh/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPacket)))
                .andExpect(status().isForbidden())
                .andExpect(content().string("Security Violation: Invalid digital wax seal!"));
    }

    @Test
    @DisplayName("Should return 500 Internal Server Error when Database/Ledger fails")
    public void testUploadPacket_InternalServerError() throws Exception { // Added 'public' here!
        doThrow(new RuntimeException("Database connection timeout"))
                .when(paymentProcessorService).processIncomingPacket(any(MeshPacket.class));

        mockMvc.perform(post("/api/mesh/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPacket)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("Internal Error: Database connection timeout"));
    }
}