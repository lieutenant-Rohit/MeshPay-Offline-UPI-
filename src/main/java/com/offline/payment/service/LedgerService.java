package com.offline.payment.service;

import com.offline.payment.config.CacheService;
import com.offline.payment.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class LedgerService {

    private final AccountRepository accountRepository;
    private final CacheService cacheService;

    public LedgerService(AccountRepository accountRepository, CacheService cacheService) {
        this.accountRepository = accountRepository;
        this.cacheService = cacheService;
    }

    @Transactional
    public void transferFunds(String senderVpa, String receiverVpa, BigDecimal amount) {
        int debited = accountRepository.atomicDebit(senderVpa, amount);
        if (debited == 0) {
            throw new RuntimeException("Insufficient funds or sender not found: " + senderVpa);
        }
        int credited = accountRepository.atomicCredit(receiverVpa, amount);
        if (credited == 0) {
            accountRepository.atomicCredit(senderVpa, amount);
            throw new RuntimeException("Receiver account not found: " + receiverVpa);
        }
        cacheService.evictAccount(senderVpa);
        cacheService.evictAccount(receiverVpa);
    }
}
