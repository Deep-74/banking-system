package com.banking.transactionservice.service;

import com.banking.transactionservice.repository.TransactionRepository;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Consumes verfication.required
 * Generates OTP and ask to verify
 */
@Service @Slf4j
@RequiredArgsConstructor
public class TransactionEventConsumer {
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;


    private static final long OTP_EXPIRY_MINUTES = 5;
    private static final String TRANSACTION_OTP_GENERATED_TOPIC = "transaction.otp.generated";

    /**
     * Consume verification.required
     * Generated OTP and ask user to verify
     *
     * @param payload
     */

    @KafkaListener(topics = "verification.required", groupId = "transaction-service")
    public void consumeVerificationRequired(@Payload Map<String, Object> payload) {
        try {
            String transactionId = (String) payload.get("transactionId");
            String accountNumber = (String) payload.get("accountNumber");
            String reason = (String) payload.get("reason");

            log.info("Verification required for transactionId: {}, accountNumber: {}, reason: {}", transactionId, accountNumber, reason);

            Transaction transaction = transactionRepository.findById(transactionId).orElseThrow(() -> new RuntimeException("Transaction not found"));


            if (!transaction.getStatus().equals(TransactionStatus.PROCESSING)) {
                log.info("Transaction {} is processing skiping the transaction ", transactionId);
                return;
            }
            //Generate 6 Digit OTP

            String OTP = String.format("%06d", (int) (Math.random() * 900000) + 100000);

            //Store OTP in redis

            String otpkey = "verification:otp:" + transactionId;

            redisTemplate.opsForValue().set(otpkey, OTP, OTP_EXPIRY_MINUTES, TimeUnit.MINUTES);

            //Update Status to PENDING_VERIFICATION"
            transaction.setStatus(TransactionStatus.PENDING_VERIFICATION);
            transactionRepository.save(transaction);

            log.info("OTP generated for transactionId: {}, OTP: {}, valid for {} minutes", transactionId, OTP, OTP_EXPIRY_MINUTES);

            //NOTIFY USER VIA SMS/EMAIL
            //PUBLISH THE EVENT

            Map<String, Object> otpEvent = Map.of(
                    "transactionId", transactionId,
                    "accountNumber", accountNumber,
                    "reason", reason,
                    "otp", OTP
            );

            kafkaTemplate.send(TRANSACTION_OTP_GENERATED_TOPIC, transactionId, otpEvent);
        } catch (Exception ex) {
            log.error("Error processing verification.required event: {}", ex.getMessage(), ex);
        }
    }

    @KafkaListener(topics = "fraud.check.clean", groupId = "transaction-service")
    public void consumeFraudCheckCleanResult(@Payload Map<String, Object> payload) {
        try {
            String transactionId = (String) payload.get("transactionId");
            String accountNumber = (String) payload.get("accountNumber");

            transactionService.processCleanResult(transactionId);


        } catch (Exception ex)
        {
            log.error("Error processing fraud.check.clean.result event: {}", ex.getMessage(), ex);
        }
    }
}
