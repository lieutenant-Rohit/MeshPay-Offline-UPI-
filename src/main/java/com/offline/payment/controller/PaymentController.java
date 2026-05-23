package com.offline.payment.controller;

import com.offline.payment.model.MeshPacket;
import com.offline.payment.service.PaymentProcessorService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentProcessorService paymentProcessorService;
    private final RestTemplate restTemplate = new RestTemplate();
    private static final String VISUALIZER_URL = System.getenv().getOrDefault("VISUALIZER_URL", "");

    public PaymentController(PaymentProcessorService paymentProcessorService) {
        this.paymentProcessorService = paymentProcessorService;
    }

    @PostMapping("/mesh/upload")
    public ResponseEntity<?> uploadPacket(@Valid @RequestBody MeshPacket packet) {
        reportToVisualizer("RECEIVED", packet, "Bank received packet");
        try {
            paymentProcessorService.processIncomingPacket(packet);
            reportToVisualizer("SETTLED", packet, "Transaction settled successfully");
            return ResponseEntity.ok("Transaction Processed and Settled Successfully!");
        } catch (SecurityException e) {
            e.printStackTrace();
            reportToVisualizer("REJECTED", packet, "Security Violation: " + e.getMessage());
            return ResponseEntity.status(403).body("Security Violation: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            reportToVisualizer("REJECTED", packet, "Error: " + e.getMessage());
            return ResponseEntity.status(500).body("Internal Error: " + e.getMessage());
        }
    }

    private void reportToVisualizer(String action, MeshPacket packet, String message) {
        if (VISUALIZER_URL.isEmpty()) return;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("node_id", "Bank");
            body.put("packet_id", packet.getPacketId());
            body.put("action", action);
            body.put("ttl", packet.getTtl());
            body.put("message", message);
            body.put("timestamp", System.currentTimeMillis());
            restTemplate.postForEntity(VISUALIZER_URL + "/hop", body, String.class);
        } catch (Exception ignored) {
        }
    }
}
