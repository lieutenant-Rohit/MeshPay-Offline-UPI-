package com.offline.payment.controller;

import com.offline.payment.model.MeshPacket;
import com.offline.payment.service.PaymentProcessorService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentProcessorService paymentProcessorService;

    // Spring automatically injects your master processor service here
    public PaymentController(PaymentProcessorService paymentProcessorService) {
        this.paymentProcessorService = paymentProcessorService;
    }

    /**
     * THE BANK'S PUBLIC GATEWAY ENDPOINT
     * Dave's gateway phone hits this exact URL when uploading a packet.
     */
    @PostMapping("/mesh/upload")
    public ResponseEntity<?> uploadPacket(@Valid @RequestBody MeshPacket packet) {
        try {
            // Send the packet down your secure verification pipeline
            paymentProcessorService.processIncomingPacket(packet);

            return ResponseEntity.ok("Transaction Processed and Settled Successfully!");
        } catch (SecurityException e) {
            // Catch clone attacks, invalid signatures, or expired packets
            return ResponseEntity.status(403).body("Security Violation: " + e.getMessage());
        } catch (Exception e) {
            // Catch ledger or general processing issues
            return ResponseEntity.status(500).body("Internal Error: " + e.getMessage());
        }
    }
}