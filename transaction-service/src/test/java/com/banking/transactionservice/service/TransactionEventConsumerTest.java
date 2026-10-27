package com.banking.transactionservice.service;

import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionEventConsumerTest {

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private TransactionService transactionService;
    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private TransactionEventConsumer consumer;

    private Transaction transaction(TransactionStatus status) {
        Transaction tx = new Transaction();
        tx.setId("tx-1");
        tx.setSenderAccountNumber("111111111111");
        tx.setReceiverAccountNumber("222222222222");
        tx.setStatus(status);
        return tx;
    }

    private Map<String, Object> verificationPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionId", "tx-1");
        payload.put("accountNumber", "111111111111");
        payload.put("reason", "High value transfer");
        return payload;
    }

    // ---------- consumeVerificationRequired ----------

    @Test
    void consumeVerificationRequired_processingTransaction_generatesOtpStoresItAndPublishes() {
        Transaction tx = transaction(TransactionStatus.PROCESSING);
        when(transactionRepository.findById("tx-1")).thenReturn(Optional.of(tx));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        consumer.consumeVerificationRequired(verificationPayload());

        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("verification:otp:tx-1"), otpCaptor.capture(), eq(5L), eq(TimeUnit.MINUTES));
        String otp = otpCaptor.getValue();
        assertThat(otp).hasSize(6).containsOnlyDigits();
        assertThat(Integer.parseInt(otp)).isBetween(100000, 999999);

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING_VERIFICATION);
        verify(transactionRepository).save(tx);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("transaction.otp.generated"), eq("tx-1"), eventCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) eventCaptor.getValue();
        assertThat(event).containsEntry("otp", otp).containsEntry("transactionId", "tx-1");
    }

    @Test
    void consumeVerificationRequired_alreadyCompleted_isSkipped() {
        when(transactionRepository.findById("tx-1"))
                .thenReturn(Optional.of(transaction(TransactionStatus.COMPLETED)));

        consumer.consumeVerificationRequired(verificationPayload());

        verify(redisTemplate, never()).opsForValue();
        verify(transactionRepository, never()).save(any());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void consumeVerificationRequired_unknownTransaction_exceptionIsSwallowed() {
        when(transactionRepository.findById("tx-1")).thenReturn(Optional.empty());

        consumer.consumeVerificationRequired(verificationPayload()); // must not propagate

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    // ---------- consumeFraudCheckCleanResult ----------

    @Test
    void consumeFraudCheckCleanResult_delegatesToService() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionId", "tx-1");
        payload.put("accountNumber", "111111111111");

        consumer.consumeFraudCheckCleanResult(payload);

        verify(transactionService).processCleanResult("tx-1");
    }

    @Test
    void consumeFraudCheckCleanResult_serviceThrows_exceptionIsSwallowed() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionId", "tx-1");
        doThrow(new RuntimeException("boom")).when(transactionService).processCleanResult(anyString());

        consumer.consumeFraudCheckCleanResult(payload); // must not propagate
    }
}

