package com.offline.payment.service;

import com.offline.payment.model.Account;
import com.offline.payment.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class LedgerService {

    private final AccountRepository accountRepository;

    public LedgerService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * @Transactional ensures that if the server crashes halfway through,
     * it rolls back the database so money doesn't disappear into the void!
     */
    @Transactional
    public void transferFunds(String senderVpa, String receiverVpa, BigDecimal amount) {

        // 1. Fetch both bank accounts
        Account sender = accountRepository.findById(senderVpa)
                .orElseThrow(() -> new RuntimeException("Sender account not found in Ledger"));

        Account receiver = accountRepository.findById(receiverVpa)
                .orElseThrow(() -> new RuntimeException("Receiver account not found in Ledger"));

        // 2. Check if Alice actually has enough money
        if (sender.getBalance().compareTo(amount) < 0) {
            throw new RuntimeException("Insufficient funds! Transfer rejected.");
        }

        // 3. Move the money in Java memory
        sender.setBalance(sender.getBalance().subtract(amount));
        receiver.setBalance(receiver.getBalance().add(amount));

        // 4. Save the new balances to the Database
        accountRepository.save(sender);
        accountRepository.save(receiver);

        // Note: Because of your @Version tag in Account.java, if two payments
        // try to deduct money from Alice at the exact same millisecond,
        // Spring Boot will safely reject one of them!
    }
}