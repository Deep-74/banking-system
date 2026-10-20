package com.banking.accountservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Service @Slf4j
public class AccountEventConsumer
{
    @Autowired
    AccountService accountService;

    @KafkaListener(topics = "transaction.completed")
    public void consumeTransactionEvent(@Payload Map<String,Object> payload)
    {
        try{
            String reciverAccountNumber = (String) payload.get("receiverAccountNumber");
            BigDecimal amount = new BigDecimal((String) payload.get("amount"));

            log.info("Received transaction event for account: {} with amount: {}", reciverAccountNumber, amount);
            accountService.creditBalance(reciverAccountNumber, amount);

        }
        catch(Exception e)
        {
            log.error("Error processing transaction event: {}", e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Consume fraud.detected event from kafka
     * Blocks the flagged account
     * @param payload
     */

    @KafkaListener(topics = "fraud.detected", groupId = "account-service-group")
    public void consumeFraudDetectionEvent(@Payload Map<String,Object> payload)
    {
        try{
            String accountNumber = (String) payload.get("accountNumber");
            log.info("Received fraud detection event for account: {}", accountNumber);
            accountService.blockAccount(accountNumber);

        }
        catch(Exception e)
        {
            log.error("Error processing fraud detection event: {}", e.getMessage(), e);
        }
    }
}
