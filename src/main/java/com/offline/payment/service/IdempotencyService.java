package com.offline.payment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final ConcurrentHashMap<String, Long> cache = new ConcurrentHashMap<>();

    private static final long ENTRY_TTL_MS = 60_000;

    public IdempotencyService() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::cleanup, 10_000, 10_000, TimeUnit.MILLISECONDS);
    }

    public String generateKey(String senderVpa, String receiverVpa, BigDecimal amount, long signedAt) {
        try {
            long minuteBucket = signedAt / 60_000;
            String raw = senderVpa + "|" + receiverVpa + "|" + amount.toPlainString() + "|" + minuteBucket;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate idempotency key", e);
        }
    }

    public boolean isDuplicate(String key) {
        Long now = System.currentTimeMillis();
        Long existing = cache.putIfAbsent(key, now);
        if (existing != null) {
            log.warn("Double-spend detected: [{}]", key);
            return true;
        }
        return false;
    }

    private void cleanup() {
        long cutoff = System.currentTimeMillis() - ENTRY_TTL_MS;
        cache.entrySet().removeIf(e -> e.getValue() < cutoff);
    }
}
