package com.offline.payment.config;

import com.offline.payment.model.MeshPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class AsyncEventService {

    private static final Logger log = LoggerFactory.getLogger(AsyncEventService.class);
    private final RestTemplate restTemplate = new RestTemplate();
    private final String visualizerUrl;

    public AsyncEventService() {
        this.visualizerUrl = System.getenv().getOrDefault("VISUALIZER_URL", "");
    }

    @Async("visualizerExecutor")
    public void reportToVisualizer(String action, MeshPacket packet, String message) {
        if (visualizerUrl.isEmpty()) return;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("node_id", "Bank");
            body.put("packet_id", packet.getPacketId());
            body.put("action", action);
            body.put("ttl", packet.getTtl());
            body.put("message", message);
            body.put("timestamp", System.currentTimeMillis());
            restTemplate.postForEntity(visualizerUrl + "/hop", body, String.class);
        } catch (Exception e) {
            log.warn("Failed to report to visualizer: {}", e.getMessage());
        }
    }
}
