package com.offline.payment.service;

import com.offline.payment.config.AsyncEventService;
import com.offline.payment.model.MeshPacket;
import com.offline.payment.model.OutboxEvent;
import com.offline.payment.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final AsyncEventService asyncEventService;

    public OutboxService(OutboxEventRepository outboxEventRepository,
                         AsyncEventService asyncEventService) {
        this.outboxEventRepository = outboxEventRepository;
        this.asyncEventService = asyncEventService;
    }

    @Transactional
    public void publishEvent(String packetHash, String senderVpa, String receiverVpa, BigDecimal amount) {
        OutboxEvent event = new OutboxEvent(packetHash, senderVpa, receiverVpa, amount);
        outboxEventRepository.save(event);
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void processOutbox() {
        List<OutboxEvent> pending = outboxEventRepository.findByStatus(OutboxEvent.OutboxStatus.PENDING);
        for (OutboxEvent event : pending) {
            try {
                if (asyncEventService != null) {
                    MeshPacket dummy = new MeshPacket();
                    dummy.setPacketId(event.getPacketHash().substring(0, Math.min(36, event.getPacketHash().length())));
                    dummy.setSenderVpa(event.getSenderVpa());
                    dummy.setTtl(0);
                    asyncEventService.reportToVisualizer("SETTLED", dummy,
                            event.getSenderVpa() + " paid ₹" + event.getAmount() + " to " + event.getReceiverVpa());
                }
                event.setStatus(OutboxEvent.OutboxStatus.PUBLISHED);
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.warn("Outbox processing failed for {}: {}", event.getId(), e.getMessage());
                event.setStatus(OutboxEvent.OutboxStatus.FAILED);
                outboxEventRepository.save(event);
            }
        }
    }
}
