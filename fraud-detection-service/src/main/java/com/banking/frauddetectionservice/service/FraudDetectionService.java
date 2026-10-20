package com.banking.frauddetectionservice.service;

import com.banking.frauddetectionservice.client.AccountServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import model.FraudCheckResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service @Slf4j @RequiredArgsConstructor
public class FraudDetectionService {
    private final AccountServiceClient accountServiceClient;

    private static final String VERIFICATION_REQUIRED_TOPIC = "verification.required";
    private static final String FRAUD_CHECK_CLEAN_RESULT_TOPIC = "fraud.check.clean";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String,String> redisTemplate;


    @Value("${fraud.max-transaction-per-minute}")
    private double maxTransactionPerMinute;

    @Value("${fraud.suspicious-amount-multiplier}")
    private double suspiciousAmountMultiplier;

    @Value("${fraud.max-balance-percentage}")
    private double maxBalancePercentage;


    public void checkTransaction(Map<String, Object> payload) {


        String transactionId = (String) payload.get("transactionId");
        String accountNumber = (String) payload.get("accountNumber");

        BigDecimal amount = new BigDecimal(payload.get("amount").toString());


        //Fetch real Balance from account service using feign client

        BigDecimal senderBalance = accountServiceClient.getBalance(accountNumber);

        log.info("Checking transaction : {} account :{} amount :{} accountBalance :{}", transactionId, accountNumber, amount, senderBalance);

        FraudCheckResult fraudCheckResult = performFraudChecks(accountNumber, amount, senderBalance);

        if (fraudCheckResult.isFraud())
        {  //fraud is detected now verify

            log.info("Suspicious activity detected - account: {} - requesting OTP verification for reason: {}",
                    accountNumber, fraudCheckResult.getReason());

            Map<String, Object> verificationEvent = new HashMap<>();

            verificationEvent.put("transactionId", transactionId);
            verificationEvent.put("accountNumber", accountNumber);
            verificationEvent.put("amount", amount);
            verificationEvent.put("reason", fraudCheckResult.getReason());

            kafkaTemplate.send(VERIFICATION_REQUIRED_TOPIC, transactionId, verificationEvent);

        } else {
            //transaction is clean

            log.info("Transaction is clean ");
            Map<String, Object> transactionCleanEvent = new HashMap<>();

            transactionCleanEvent.put("transactionId", transactionId);
            transactionCleanEvent.put("isFraud", false);
            transactionCleanEvent.put("reason", null);

            kafkaTemplate.send(FRAUD_CHECK_CLEAN_RESULT_TOPIC, transactionId, transactionCleanEvent);

        }
    }

    private FraudCheckResult performFraudChecks(String accountNumber, BigDecimal amount, BigDecimal senderBalance) {
        //velocity check , limit on n.o of transaction in a minute (say.10) as usually hacker will run script that will trigger multiple
        // transaction in a minute . verify user if this occurs via otp. otp to ensure that authenticated user is doing this transaction


        // velocity check
        if (isCVelocityExceeded(accountNumber)) {
            return new FraudCheckResult(
                    true, "Too many transactions within 60 seconds velocity limit exceeded");

        }

        // pattern 2 AmountCheck
        if (isAmoutSuspicious(accountNumber, amount)) {
            return new FraudCheckResult(
                    true, "Unusal transaction amount - exceeds by 3x");

        }

        // Pattern 3 balanceCheck
        if (senderBalance.compareTo(BigDecimal.ZERO) > 0 && isBalanceCheckFailed(senderBalance, amount)) {
            return new FraudCheckResult(true, "Transaction exceeds 90% of account balance");
        }


        return new FraudCheckResult(false, "ALL Fraud check passed");
    }

     private boolean isCVelocityExceeded(String accoutNumber)
     {
         String key  = "fraud.velocity" + accoutNumber;
         Long count = redisTemplate.opsForValue().increment(key);

         if(count != null && count == 1)
         {
                redisTemplate.expire(key,60, TimeUnit.SECONDS);

         }

         log.info("Velocity check - account: {} count: {}/{}",accoutNumber,count,maxTransactionPerMinute);

         return count != null && count>maxTransactionPerMinute;
     }

     private boolean isAmoutSuspicious(String accountNumber, BigDecimal amount)
     {
         String avgKey = "fraud:avg_amount" + accountNumber;
         String avgStr = redisTemplate.opsForValue().get(avgKey);

         if(avgStr == null ){
             redisTemplate.opsForValue().set(avgKey,amount.toString());
             return false;
         }

         BigDecimal avgAmount  = new BigDecimal(avgStr);
         BigDecimal threshold = avgAmount.multiply(BigDecimal.valueOf(suspiciousAmountMultiplier));

         //updating running average

            BigDecimal newAvg = avgAmount.add(amount).divide(BigDecimal.valueOf(2),2, RoundingMode.HALF_UP);


            redisTemplate.opsForValue().set(avgKey,newAvg.toString());

            log.info("Amount check -amount: {} threshold: {} suspicious: {} ",amount,threshold,amount.compareTo(threshold)>0);


            return amount.compareTo(threshold)>0;

     }

     private boolean isBalanceCheckFailed(BigDecimal senderBalance , BigDecimal amount)
     {
         BigDecimal maxAllowed = senderBalance.multiply(
               BigDecimal.valueOf(maxBalancePercentage));

            log.info("Balance check -amount: {} maxAllowed: {} suspicious {}", amount,maxAllowed,amount.compareTo(maxAllowed)>0);

            return amount.compareTo(maxAllowed)>0;
     }


}



