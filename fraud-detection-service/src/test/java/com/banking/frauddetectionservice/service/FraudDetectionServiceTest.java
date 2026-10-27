package com.banking.frauddetectionservice.service;

import com.banking.frauddetectionservice.client.AccountServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class FraudDetectionServiceTest {

    private static final String ACCOUNT = "111111111111";
    private static final String TX_ID = "tx-1";
    private static final String VERIFICATION_TOPIC = "verification.required";
    private static final String CLEAN_TOPIC = "fraud.check.clean";
    private static final String VELOCITY_KEY = "fraud.velocity" + ACCOUNT;
    private static final String AVG_KEY = "fraud:avg_amount" + ACCOUNT;

    @Mock
    private AccountServiceClient accountServiceClient;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private FraudDetectionService service;

    @BeforeEach
    void setThresholds() {
        // Values from application.yaml; @Value fields are not set by constructor injection.
        ReflectionTestUtils.setField(service, "maxTransactionPerMinute", 5.0);
        ReflectionTestUtils.setField(service, "suspiciousAmountMultiplier", 5.0);
        ReflectionTestUtils.setField(service, "maxBalancePercentage", 0.90);
    }

    private Map<String, Object> payload(String amount) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionId", TX_ID);
        payload.put("accountNumber", ACCOUNT);
        payload.put("amount", amount);
        return payload;
    }

    private void stubBalance(String balance) {
        when(accountServiceClient.getBalance(ACCOUNT)).thenReturn(new BigDecimal(balance));
    }

    private void stubRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ---------- clean transactions ----------

    @Test
    void firstTransaction_noHistory_isClean_andSeedsAverage() {
        stubBalance("1000");
        stubRedis();
        when(valueOps.increment(VELOCITY_KEY)).thenReturn(1L);
        when(valueOps.get(AVG_KEY)).thenReturn(null);

        service.checkTransaction(payload("100"));

        verify(valueOps).set(AVG_KEY, "100");
        verify(kafkaTemplate).send(eq(CLEAN_TOPIC), eq(TX_ID), any());
        verify(kafkaTemplate, never()).send(eq(VERIFICATION_TOPIC), anyString(), any());
    }

    @Test
    void normalAmount_isCleanAndUpdatesRunningAverage() {
        stubBalance("1000");
        stubRedis();
        when(valueOps.increment(VELOCITY_KEY)).thenReturn(2L);
        when(valueOps.get(AVG_KEY)).thenReturn("100");

        service.checkTransaction(payload("200"));

        // running average: (100 + 200) / 2 = 150.00
        verify(valueOps).set(AVG_KEY, "150.00");
        verify(kafkaTemplate).send(eq(CLEAN_TOPIC), eq(TX_ID), any());
    }

    @Test
    void zeroBalance_skipsBalanceCheck() {
        stubBalance("0");
        stubRedis();
        when(valueOps.increment(VELOCITY_KEY)).thenReturn(1L);
        when(valueOps.get(AVG_KEY)).thenReturn(null);

        service.checkTransaction(payload("500"));

        verify(kafkaTemplate).send(eq(CLEAN_TOPIC), eq(TX_ID), any());
    }

    // ---------- velocity check ----------

    @Test
    void velocityExceeded_requestsVerification() {
        stubBalance("1000");
        stubRedis();
        when(valueOps.increment(VELOCITY_KEY)).thenReturn(6L);

        service.checkTransaction(payload("100"));

        verify(kafkaTemplate).send(eq(VERIFICATION_TOPIC), eq(TX_ID), any());
        verify(kafkaTemplate, never()).send(eq(CLEAN_TOPIC), anyString(), any());
    }

    @Test
    void velocityExactlyAtLimit_isNotFlagged() {
        stubBalance("1000");
        stubRedis();
        when(valueOps.increment(VELOCITY_KEY)).thenReturn(5L);
        when(valueOps.get(AVG_KEY)).thenReturn(null);

        service.checkTransaction(payload("100"));

        verify(kafkaTemplate).send(eq(CLEAN_TOPIC), eq(TX_ID), any());
    }

    @Test
    void firstIncrement_setsSixtySecondWindow() {
        stubBalance("1000");
        stubRedis();
        when(valueOps.increment(VELOCITY_KEY)).thenReturn(1L);
        when(valueOps.get(AVG_KEY)).thenReturn(null);

        service.checkTransaction(payload("100"));

        verify(redisTemplate).expire(VELOCITY_KEY, 60, TimeUnit.SECONDS);
    }

    @Test
    void laterIncrement_doesNotResetWindow() {
        stubBalance("1000");
        stubRedis();
        when(valueOps.increment(VELOCITY_KEY)).thenReturn(3L);
        when(valueOps.get(AVG_KEY)).thenReturn(null);

        service.checkTransaction(payload("100"));

        verify(redisTemplate, never()).expire(anyString(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    // ---------- amount check ----------

    @Test
    void amountAboveFiveTimesAverage_requestsVerification() {
        stubBalance("100000");
        stubRedis();
        when(valueOps.increment(VELOCITY_KEY)).thenReturn(1L);
        when(valueOps.get(AVG_KEY)).thenReturn("100");

        service.checkTransaction(payload("600")); // threshold 500

        verify(kafkaTemplate).send(eq(VERIFICATION_TOPIC), eq(TX_ID), any());
    }

    @Test
    void amountWithinFiveTimesAverage_isClean() {
        stubBalance("100000");
        stubRedis();
        when(valueOps.increment(VELOCITY_KEY)).thenReturn(1L);
        when(valueOps.get(AVG_KEY)).thenReturn("100");

        service.checkTransaction(payload("500")); // equal to threshold is not suspicious

        verify(kafkaTemplate).send(eq(CLEAN_TOPIC), eq(TX_ID), any());
    }

    // ---------- balance check ----------

    @Test
    void amountOverNinetyPercentOfBalance_requestsVerification() {
        stubBalance("1000");
        stubRedis();
        when(valueOps.increment(VELOCITY_KEY)).thenReturn(1L);
        when(valueOps.get(AVG_KEY)).thenReturn(null);

        service.checkTransaction(payload("950")); // max allowed 900

        verify(kafkaTemplate).send(eq(VERIFICATION_TOPIC), eq(TX_ID), any());
    }

    @Test
    void amountUnderNinetyPercentOfBalance_isClean() {
        stubBalance("1000");
        stubRedis();
        when(valueOps.increment(VELOCITY_KEY)).thenReturn(1L);
        when(valueOps.get(AVG_KEY)).thenReturn(null);

        service.checkTransaction(payload("900"));

        verify(kafkaTemplate).send(eq(CLEAN_TOPIC), eq(TX_ID), any());
    }

    // ---------- failure propagation ----------

    @Test
    void accountServiceFailure_propagatesAndPublishesNothing() {
        when(accountServiceClient.getBalance(ACCOUNT)).thenThrow(new RuntimeException("account service down"));

        assertThatThrownBy(() -> service.checkTransaction(payload("100")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("account service down");

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }
}

