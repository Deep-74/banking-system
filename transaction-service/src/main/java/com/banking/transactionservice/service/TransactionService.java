package com.banking.transactionservice.service;

import com.banking.transactionservice.client.AccountServiceClient;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.event.TransactionInitiatedEvent;
import com.banking.transactionservice.repository.TransactionRepository;
import com.banking.transactionservice.exception.TransactionNotFoundException;
import com.banking.transactionservice.exception.OTPExpiredException;
import com.banking.transactionservice.exception.InvalidOTPException;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.entity.TransationType;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Data
@RequiredArgsConstructor
@Slf4j
public class TransactionService
{
    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;
    private final KafkaTemplate<String,Object> kafkaTemplate;
    private final RedisTemplate<String,String> redisTemplate;

    private static final String TRANSACTION_INITIATED_TOPIC = "transaction.initiated";
    private static final String TRANSACTION_COMPLETED_TOPIC = "transaction.completed";
    private static final String TRANSACTION_REJECTED_TOPIC = "transaction.refunded";
    private static final String FRAUD_DETECTED_TOPIC = "fraud.detected";


    /**
     *  SAGA STEP -1 :  INIITATE TRASNFER
     *  Deducts from sender via feign
     *  Saves transaction as PROCESSING
     *  Publish to kafka topic for fraud check
     *  Returns
     */

    public TransactionResponse transfer(TransferRequest transferRequest)
    {
      log.info("SAGA START - TRANSFER: {} -> {} amount {} " ,transferRequest.getSenderAccountNumber(), transferRequest.getReceiverAccountNumber(), transferRequest.getAmount());

       //SAGA STEP 1: Deduct from sender account via feign client
        accountServiceClient.deductBalance(transferRequest.getSenderAccountNumber(), transferRequest.getAmount());

        Transaction transaction = new Transaction();
        transaction.setSenderAccountNumber(transferRequest.getSenderAccountNumber());
        transaction.setReceiverAccountNumber(transferRequest.getReceiverAccountNumber());
        transaction.setAmount(transferRequest.getAmount());
        transaction.setType(TransationType.TRANSFER);
        transaction.setStatus(TransactionStatus.PROCESSING);
        transaction.setDescription(transferRequest.getDescription());
        transaction.setReferenceNumber(UUID.randomUUID().toString());

        Transaction savedTransaction = transactionRepository.save(transaction);
        log.info("Transaction saved with ID: {}", savedTransaction.getId());


        // SAGA STEP 2: Publish to kafka topic for fraud check

//        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
//                savedTransaction.getId(),
//                savedTransaction.getSenderAccountNumber(),
//                savedTransaction.getReceiverAccountNumber(),
//                savedTransaction.getAmount(),
//                savedTransaction.getDescription()
//                );


        Map<String,Object> event = Map.of(
                "transactionId", savedTransaction.getId(),
                "accountNumber", savedTransaction.getSenderAccountNumber(),
                "receiverAccountNumber", savedTransaction.getReceiverAccountNumber(),
                "amount", savedTransaction.getAmount().toString(),
                "description", savedTransaction.getDescription());


            kafkaTemplate.send(TRANSACTION_INITIATED_TOPIC,savedTransaction.getId(), event);

            log.info("SAGA STEP 2 Transaction Initiated Event published to Kafka for transaction ID: {}", savedTransaction.getId());


        return mapToResponse(savedTransaction); // Placeholder
    }

    public TransactionResponse verifyOTP(String transactionId, String otp)
    {
        log.info("OTP verification requested for transaction ID: {}", transactionId);

        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> {
                    log.error("Transaction not found with ID: {}", transactionId);
                    return new TransactionNotFoundException("Transaction not found with ID: " + transactionId);
                });

        String otpkey = "verification:otp:" + transactionId;

        String storedOtp = (String) redisTemplate.opsForValue().get(otpkey);

        if(storedOtp == null)
        {
            //OTP EXPIRED
            log.warn("OTP expired for transaction ID: {}", transactionId);
            throw new OTPExpiredException("OTP has expired for transaction ID: " + transactionId + ". Transaction has been cancelled and amount refunded.");
        }
        if(!storedOtp.equals(otp))
        {
            //OTP MISMATCH BLOCK ACCOUNT AND REFUND
            log.warn("Wrong otp - blocking account and refunding transaction ID: {}", transactionId);

            redisTemplate.delete(otpkey);

            blockAccountAndCompensate(transaction,"Wrong OTP entered - transaction cancelled account blocked for security");
            throw new InvalidOTPException("Invalid OTP provided. Account has been blocked for security. Please contact your bank.");
        }

        //OTP Correct - Complete Transaction
        log.info("OTP verified successfully for transaction ID: {}", transactionId);

        redisTemplate.delete(otpkey);

        completeTransaction(transaction);
        return mapToResponse(transaction);
    }



    public TransactionResponse getTransaction(String transactionId)
    {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> {
                    log.error("Transaction not found with ID: {}", transactionId);
                    return new TransactionNotFoundException("Transaction not found with ID: " + transactionId);
                });

        return mapToResponse(transaction);
    }

    public List<TransactionResponse> getTransactionHistory(String accountNumber)
    {
        List<Transaction> transactions = transactionRepository.findBySenderAccountNumberOrderByCreatedAtDesc(accountNumber);
        return transactions.stream().map(this::mapToResponse).toList();
    }

    private void compensateTransaction(Transaction transaction, String reason)
    {
        log.info("SAGA COMPENSATION - Refunding: {}  amount : {}", transaction.getSenderAccountNumber(), transaction.getAmount());

        //CREDIT MONEY BACK TO SENDER SYNCHRONOUSLY

        accountServiceClient.creditBalance(transaction.getSenderAccountNumber(), transaction.getAmount());

        transaction.setStatus(TransactionStatus.PENDING);
        transaction.setFailureReason(reason +  "- SAGA Compensation executed, amount refunded at" + LocalDateTime.now());

        transactionRepository.save(transaction);

        //PUBLISH refund event - Notification service will alert user

        Map<String,Object> refundEvent = Map.of(
                "transactionId", transaction.getId(),
                "senderAccountNumber", transaction.getSenderAccountNumber(),
                "receiverAccountNumber", transaction.getReceiverAccountNumber(),
                "amount", transaction.getAmount(),
                "reason", reason
        );

        kafkaTemplate.send(TRANSACTION_REJECTED_TOPIC, transaction.getId(), refundEvent);

    }

    private void blockAccountAndCompensate(Transaction transaction, String reason)
    {
        log.info("SAGA COMPENSATION - Blocking account and Refunding: {}  amount : {}", transaction.getSenderAccountNumber(), transaction.getAmount());

        //publish fraud.detected -> Account service will block account

        Map<String, Object> fraudEvent = Map.of(
                "transactionId", transaction.getId(),
                "accountNumber", transaction.getSenderAccountNumber(),
                "receiverAccountNumber", transaction.getReceiverAccountNumber(),
                "amount", transaction.getAmount(),
                "reason", reason
        );

        kafkaTemplate.send(FRAUD_DETECTED_TOPIC, transaction.getId(), fraudEvent);


        log.warn("fraud detected published - account: {} will be blocked ,Kindly contact to the bank ", transaction.getSenderAccountNumber());

//        accountServiceClient.creditBalance(transaction.getSenderAccountNumber(), transaction.getAmount());

        //SAGA COMPENSATION - refund Sender

        compensateTransaction(transaction, reason);

    }

    private void completeTransaction(Transaction transaction)
    {
        //CREDIT MONEY TO RECEIVER SYNCHRONOUSLY

        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setCompletedAt(LocalDateTime.now());
        transactionRepository.save(transaction);

        //PUBLISH completion event - Notification service will alert user

        Map<String,Object> completionEvent = Map.of(
                "transactionId", transaction.getId(),
                "senderAccountNumber", transaction.getSenderAccountNumber(),
                "receiverAccountNumber", transaction.getReceiverAccountNumber(),
                "amount", transaction.getAmount()
        );

        kafkaTemplate.send(TRANSACTION_COMPLETED_TOPIC, transaction.getId(), completionEvent);

        log.info("SAGA STEP 2    - Transaction {} completed",transaction.getId());


    }

    public void processCleanResult(String transactionId)
    {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> {
                    log.error("Transaction not found with ID: {}", transactionId);
                    return new TransactionNotFoundException("Transaction not found with ID: " + transactionId);
                });


        if(transaction.getStatus() != TransactionStatus.PROCESSING)
        {
            log.warn("Transaction {} not processing - skipping",transactionId);
            return;
        }

        completeTransaction(transaction);


    }




    private  TransactionResponse mapToResponse(Transaction transaction)
    {
        TransactionResponse response = new TransactionResponse();
        response.setId(transaction.getId());
        response.setSenderAccountNumber(transaction.getSenderAccountNumber());
        response.setReceiverAccountNumber(transaction.getReceiverAccountNumber());
        response.setAmount(transaction.getAmount());
        response.setType(transaction.getType());
        response.setStatus(transaction.getStatus());
        response.setDescription(transaction.getDescription());
        response.setReferenceNumber(transaction.getReferenceNumber());
        response.setCreatedAt(transaction.getCreatedAt());
        response.setCompletedAt(transaction.getCompletedAt());

        return response;
    }


}
