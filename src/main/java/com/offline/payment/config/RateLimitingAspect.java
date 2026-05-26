package com.offline.payment.config;

import com.offline.payment.model.MeshPacket;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Profile("!test")
public class RateLimitingAspect {

    private final CacheService cacheService;

    @Value("${payment.rate-limit.per-vpa}")
    private int perVpaLimit;

    @Value("${payment.rate-limit.per-vpa-window-seconds}")
    private int perVpaWindow;

    @Value("${payment.rate-limit.global}")
    private int globalLimit;

    @Value("${payment.rate-limit.global-window-seconds}")
    private int globalWindow;

    public RateLimitingAspect(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Around("execution(* com.offline.payment.service.PaymentProcessorService.processIncomingPacket(..)) && args(packet)")
    public Object checkRateLimit(ProceedingJoinPoint pjp, MeshPacket packet) throws Throwable {
        String globalKey = "global";
        if (!cacheService.tryAcquireRateLimit(globalKey, globalLimit, globalWindow)) {
            throw new SecurityException("Global rate limit exceeded. Try again later.");
        }

        if (packet.getSenderVpa() != null) {
            String vpaKey = "vpa:" + packet.getSenderVpa();
            if (!cacheService.tryAcquireRateLimit(vpaKey, perVpaLimit, perVpaWindow)) {
                throw new SecurityException("Rate limit exceeded for " + packet.getSenderVpa() + ". Try again later.");
            }
        }

        return pjp.proceed();
    }
}
