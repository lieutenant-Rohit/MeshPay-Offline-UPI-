package com.offline.payment.controller;

import com.offline.payment.config.AsyncEventService;
import com.offline.payment.model.MeshPacket;
import com.offline.payment.service.PaymentProcessorService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentProcessorService paymentProcessorService;
    private final AsyncEventService asyncEventService;

    public PaymentController(PaymentProcessorService paymentProcessorService,
                             AsyncEventService asyncEventService) {
        this.paymentProcessorService = paymentProcessorService;
        this.asyncEventService = asyncEventService;
    }

    @PostMapping("/mesh/upload")
    public ResponseEntity<Map<String, String>> uploadPacket(@Valid @RequestBody MeshPacket packet) {
        asyncEventService.reportToVisualizer("RECEIVED", packet, "Bank received packet");
        try {
            paymentProcessorService.processIncomingPacket(packet);
            asyncEventService.reportToVisualizer("SETTLED", packet, "Transaction settled successfully");
            return ResponseEntity.ok(Map.of("status", "success", "message", "Transaction Processed and Settled Successfully!"));
        } catch (SecurityException e) {
            asyncEventService.reportToVisualizer("REJECTED", packet, "Security Violation: " + e.getMessage());
            return ResponseEntity.status(403).body(Map.of("error", "security_violation", "message", e.getMessage()));
        } catch (Exception e) {
            asyncEventService.reportToVisualizer("REJECTED", packet, "Error: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "internal_error", "message", e.getMessage()));
        }
    }
}
