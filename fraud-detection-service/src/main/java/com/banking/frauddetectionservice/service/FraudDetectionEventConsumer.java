package com.banking.frauddetectionservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service @Slf4j @RequiredArgsConstructor
public class FraudDetectionEventConsumer
{
    private  final  FraudDetectionService fraudDetectionService;

    /**
     * Consumes transaction.initiated event from kafka
     * Every Transaction is checked for fraud
     * @param payload
     */
    @KafkaListener(topics = "transaction.initiated", groupId = "fraud-detection-group")
    public void consumeTransactionInitiated(@Payload Map<String, Object> payload)
    {
        log.info("Received transaction for fraud check: {}", payload.get("transactionId"));

        try {
            fraudDetectionService.checkTransaction(payload);
        } catch (Exception e) {
            log.error("Error processing transaction initiated event for transaction ID {}: {}. Details: {}",
                    payload.get("transactionId"), e.getMessage(), e.getClass().getSimpleName());
            log.debug("Full stack trace: ", e);
        }
    }
}
