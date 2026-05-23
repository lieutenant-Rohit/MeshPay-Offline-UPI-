package com.offline.payment.config;

import com.offline.payment.model.User;
import com.offline.payment.repository.UserRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer {

    private final UserRepository userRepository;

    public DataInitializer(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadData() {
        System.out.println("📱 No pre-loaded users — phone scripts will provision dynamically.");
    }
}
