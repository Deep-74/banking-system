package com.banking.notificationservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
public class NotificationService
{
    @KafkaListener(topics = "transaction.otp.generated")
    public void consumeNotification(@Payload Map<String,Object> payload)
    {
        String accountNumber = (String) payload.get("accountNumber");
        String otp = (String) payload.get("otp");
        String transactionId = (String) payload.get("transactionId");
        String amount = (String) payload.get("amount");
        String reason = (String) payload.get("reason");

        try {
            sendAlert(accountNumber,
                    "TRANSACTION VERIFICATION REQUIRED",
                    String.format("Suspicious activity detected on your account. Reason: %s. A transaction of %s is pending verification. Your OTP is: %s (valid for 5 minutes). If this wasn't you, ignore this message.",
                            reason, amount, otp)
            );
        }
        catch (Exception e)
        {
            log.error("Error sending OTP Notification for account {} and transaction {}: {}. Exception: {}",
                    accountNumber, transactionId, e.getMessage(), e.getClass().getSimpleName());
            log.debug("Full stack trace for OTP notification: ", e);
         }

  }

  @KafkaListener(topics = "transaction.completed")
  public void consumeTransactionCompleted(@Payload Map<String,Object> payload)
  {
      try
      {
          String senderAccountNumber = (String) payload.get("senderAccountNumber");
          String receiverAccountNumber = (String) payload.get("receiverAccountNumber");
          String amount = payload.get("amount").toString();
          String transactionId = (String) payload.get("transactionId");

          // DEBIT ALERT
          sendAlert(senderAccountNumber,"DEBIT ALERT",String.format("%s debited from %s",amount,senderAccountNumber));

          //CREDIT ALERT
          sendAlert(receiverAccountNumber,"CREDIT ALERT",String.format("%s credited from account %s",amount,receiverAccountNumber));

      }
      catch (Exception e)
      {
          log.error("Error sending Transaction Completed Notification for transaction {}: {}. Exception: {}",
                  payload.get("transactionId"), e.getMessage(), e.getClass().getSimpleName());
          log.debug("Full stack trace for transaction completed notification: ", e);
      }
  }


  @KafkaListener(topics = "fraud.detected")
  public void consumeFraudDetection(@Payload Map<String,Object> payload)
  {
      try
      {
          String accountNumber = (String) payload.get("accountNumber");
          String reason = (String) payload.get("reason");

          sendAlert(accountNumber,
                  "SUSPICIOUS ACTIVITY DETECTED",
                  String.format("Your account %s has been blocked. Reason: %s. Please contact your bank immediately.",
                          accountNumber, reason));

      }
      catch (Exception e)
      {
          log.error("Error sending Fraud Detection Notification for account {}: {}. Exception: {}",
                  payload.get("accountNumber"), e.getMessage(), e.getClass().getSimpleName());
          log.debug("Full stack trace for fraud detection notification: ", e);
      }
  }

  @KafkaListener(topics = "transaction.failed")
  public void consumeTransactionFailed(@Payload Map<String,Object> payload)
  {
      try
      {
          String accountNumber = (String) payload.get("accountNumber");
          String reason = (String) payload.get("reason");
          String amount = (String) payload.get("amount");

          sendAlert(accountNumber,
                  "TRANSACTION FAILED",
                  String.format("Your transaction of %s has failed. Reason: %s. Please contact your bank immediately.",
                          amount, reason)
          );

      }

      catch (Exception e)
      {
          log.error("Error sending Transaction Failed Notification for account {}: {}. Exception: {}",
                  payload.get("accountNumber"), e.getMessage(), e.getClass().getSimpleName());
          log.debug("Full stack trace for transaction failed notification: ", e);
      }
  }


  @KafkaListener(topics = "transaction.refunded")
  public void consumeTransactionRefunded(@Payload Map<String,Object> payload)
  {
      try
      {
          String accountNumber = (String) payload.get("accountNumber");
          String reason = (String) payload.get("reason");
          String amount = (String) payload.get("amount");

          sendAlert(accountNumber,
                  "REFUND PROCESSED",
                  String.format("Your transaction of %s was cancelled. Reason: %s. %s has been refunded to account %s.",
                          amount, reason, amount, accountNumber)
          );

      }

      catch (Exception e)
      {
          log.error("Error sending Refund Notification for account {}: {}. Exception: {}",
                  payload.get("accountNumber"), e.getMessage(), e.getClass().getSimpleName());
          log.debug("Full stack trace for refund notification: ", e);
      }
  }

    @KafkaListener(topics = "payment.completed")
    public void consumePaymentCompleted(@Payload Map<String,Object> payload)
    {
        try
        {
            String accountNumber = (String) payload.get("accountNumber");
            String amount = (String) payload.get("amount");

            sendAlert(accountNumber,
                    "PAYMENT SUCCESSFUL",
                    String.format("Payment of %s has been completed. Razorpay ID: %s.",
                            amount, payload.get("razorpayPaymentId")));

        }
        catch (Exception e)
        {
            log.error("Error sending Payment Completed Notification for account {}: {}. Exception: {}",
                    payload.get("accountNumber"), e.getMessage(), e.getClass().getSimpleName());
            log.debug("Full stack trace for payment completed notification: ", e);
        }
    }


  @KafkaListener(topics = "payment.failed")
  public void consumePaymentFailed(@Payload Map<String,Object> payload)
  {
      try
      {
          String accountNumber = (String) payload.get("accountNumber");
          String amount = (String) payload.get("amount");

          sendAlert(accountNumber,
                  "PAYMENT FAILED",
                  String.format("Payment of %s could not be processed. Please try again or contact support.",
                          amount));
      }
      catch (Exception e)
      {
          log.error("Error sending Payment Failed Notification for account {}: {}. Exception: {}",
                  payload.get("accountNumber"), e.getMessage(), e.getClass().getSimpleName());
          log.debug("Full stack trace for payment failed notification: ", e);
      }
  }


  void sendAlert(String accountNumber , String subject, String message)
  {
      log.info("-------------");
      log.info("Account: {} ",accountNumber);
      log.info("Subject: {} ",subject);
      log.info("Message: {} ",message);
      log.info("-------------");
  }

}
