package com.banking.frauddetectionservice.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FraudDetectionEventConsumerTest {

    @Mock
    private FraudDetectionService fraudDetectionService;

    @InjectMocks
    private FraudDetectionEventConsumer consumer;

    private Map<String, Object> payload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionId", "tx-1");
        payload.put("accountNumber", "111111111111");
        payload.put("amount", "100");
        return payload;
    }

    @Test
    void consumeTransactionInitiated_delegatesToFraudCheck() {
        Map<String, Object> payload = payload();

        consumer.consumeTransactionInitiated(payload);

        verify(fraudDetectionService).checkTransaction(payload);
    }

    @Test
    void consumeTransactionInitiated_serviceThrows_exceptionIsSwallowed() {
        doThrow(new RuntimeException("redis down"))
                .when(fraudDetectionService).checkTransaction(anyMap());

        consumer.consumeTransactionInitiated(payload()); // must not propagate
    }
}

