package com.banking.accountservice.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class AccountEventConsumerTest {

    @Mock
    private AccountService accountService;

    @InjectMocks
    private AccountEventConsumer consumer;

    @Test
    void consumeTransactionEvent_validPayload_creditsReceiver() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("receiverAccountNumber", "123456789012");
        payload.put("amount", "250.00");

        consumer.consumeTransactionEvent(payload);

        verify(accountService).creditBalance("123456789012", new BigDecimal("250.00"));
    }

    @Test
    void consumeTransactionEvent_invalidAmount_doesNotCredit() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("receiverAccountNumber", "123456789012");
        payload.put("amount", "not-a-number");

        consumer.consumeTransactionEvent(payload);

        verifyNoInteractions(accountService);
    }

    @Test
    void consumeTransactionEvent_serviceThrows_exceptionIsSwallowed() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("receiverAccountNumber", "123456789012");
        payload.put("amount", "10");
        doThrow(new RuntimeException("boom")).when(accountService).creditBalance(any(), any());

        consumer.consumeTransactionEvent(payload); // must not propagate
    }

    @Test
    void consumeFraudDetectionEvent_validPayload_blocksAccount() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("accountNumber", "123456789012");

        consumer.consumeFraudDetectionEvent(payload);

        verify(accountService).blockAccount("123456789012");
    }

    @Test
    void consumeFraudDetectionEvent_serviceThrows_exceptionIsSwallowed() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("accountNumber", "000");
        doThrow(new RuntimeException("not found")).when(accountService).blockAccount("000");

        consumer.consumeFraudDetectionEvent(payload); // must not propagate
    }
}

