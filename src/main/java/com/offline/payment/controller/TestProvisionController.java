package com.offline.payment.controller;

import com.offline.payment.config.CacheService;
import com.offline.payment.model.Account;
import com.offline.payment.model.User;
import com.offline.payment.repository.AccountRepository;
import com.offline.payment.repository.UserRepository;
import com.offline.payment.security.ServerKeyHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TestProvisionController {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final ServerKeyHolder serverKeyHolder;
    private final CacheService cacheService;

    public TestProvisionController(UserRepository userRepository,
                                   AccountRepository accountRepository,
                                   ServerKeyHolder serverKeyHolder,
                                   CacheService cacheService) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.serverKeyHolder = serverKeyHolder;
        this.cacheService = cacheService;
    }

    @PostMapping("/mesh/provision")
    public Map<String, String> provisionDevice(@RequestBody Map<String, String> request) {
        String userVpa = request.get("vpa");
        String userPublicKey = request.get("publicKey");

        // Evict stale caches so re-provisioning picks up new keys
        cacheService.evictUser(userVpa);
        cacheService.evictAccount(userVpa);

        User user = userRepository.findById(userVpa).orElseGet(User::new);
        user.setVpa(userVpa);
        user.setPublicKeyBase64(userPublicKey);
        userRepository.save(user);

        if (!accountRepository.existsById(userVpa)) {
            BigDecimal startingBalance = new BigDecimal("5000.00");
            String holderName = deriveHolderName(userVpa);
            Account account = new Account(userVpa, holderName, startingBalance);
            accountRepository.save(account);
        }

        return Map.of(
                "status", "Provisioned Successfully",
                "vpa", userVpa,
                "bankPublicKey", serverKeyHolder.getEncryptionPublicKeyBase64(),
                "bankSigningKey", serverKeyHolder.getSigningPublicKeyBase64()
        );
    }

    /** Derives a display name from the VPA prefix (e.g. "alice@bank" → "Alice"). */
    private String deriveHolderName(String vpa) {
        if (vpa == null || !vpa.contains("@")) return vpa;
        String prefix = vpa.split("@")[0];
        return prefix.substring(0, 1).toUpperCase() + prefix.substring(1);
    }
}