package com.offline.payment;

import com.offline.payment.config.AsyncEventService;
import com.offline.payment.config.CacheService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PaymentApplicationTests {

    @MockBean
    private CacheService cacheService;

    @MockBean
    private AsyncEventService asyncEventService;

    @Test
    void contextLoads() {
    }

}
