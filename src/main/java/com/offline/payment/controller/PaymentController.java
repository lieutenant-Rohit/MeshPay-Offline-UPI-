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

    public PaymentController(PaymentProcessorService paymentProcessorService) {
        this.paymentProcessorService = paymentProcessorService;
    }

    @PostMapping("/mesh/upload")
    public ResponseEntity<?> uploadPacket(@Valid @RequestBody MeshPacket packet) {
        try {
            paymentProcessorService.processIncomingPacket(packet);
            return ResponseEntity.ok("Transaction Processed and Settled Successfully!");
        } catch (SecurityException e) {
            e.printStackTrace(); // <-- show full trace
            return ResponseEntity.status(403).body("Security Violation: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace(); // <-- show full trace
            return ResponseEntity.status(500).body("Internal Error: " + e.getMessage());
        }
    }
}