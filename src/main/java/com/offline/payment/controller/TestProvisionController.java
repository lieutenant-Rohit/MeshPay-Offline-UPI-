package com.offline.payment.controller;

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

    public TestProvisionController(UserRepository userRepository,
                                   AccountRepository accountRepository,
                                   ServerKeyHolder serverKeyHolder) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.serverKeyHolder = serverKeyHolder;
    }

    /**
     * Registers a user (Alice or Bob) and creates their account.
     * Call this once for alice@bank, then once for bob@bank.
     *
     * FIX: previously hardcoded bob@upi and "Bob" regardless of the request body,
     * so alice_phone.py's payment to "bob@bank" always failed with "Receiver not found".
     * Now each call provisions exactly the VPA that was sent in the request.
     *
     * FIX #2: Always update the public key even if the user already exists.
     * This prevents stale/invalid keys (e.g. from DataInitializer) from
     * causing signature verification failures later on upload.
     */
    @PostMapping("/mesh/provision")
    public Map<String, String> provisionDevice(@RequestBody Map<String, String> request) {
        String userVpa = request.get("vpa");
        String userPublicKey = request.get("publicKey");

        // Always save/update the user's public key (upsert semantics)
        User user = userRepository.findById(userVpa).orElseGet(User::new);
        user.setVpa(userVpa);
        user.setPublicKeyBase64(userPublicKey);
        userRepository.save(user);

        // Create account only if it doesn't already exist
        if (!accountRepository.existsById(userVpa)) {
            BigDecimal startingBalance = new BigDecimal("5000.00");
            String holderName = deriveHolderName(userVpa);
            Account account = new Account(userVpa, holderName, startingBalance);
            accountRepository.save(account);
        }

        return Map.of(
                "status", "Provisioned Successfully",
                "vpa", userVpa,
                "bankPublicKey", serverKeyHolder.getPublicKeyBase64()
        );
    }

    /** Derives a display name from the VPA prefix (e.g. "alice@bank" → "Alice"). */
    private String deriveHolderName(String vpa) {
        if (vpa == null || !vpa.contains("@")) return vpa;
        String prefix = vpa.split("@")[0];
        return prefix.substring(0, 1).toUpperCase() + prefix.substring(1);
    }
}