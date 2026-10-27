package com.banking.notificationservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationServiceTest {

    record Alert(String account, String subject, String message) {}

    /** Records alerts instead of logging them. */
    static class RecordingNotificationService extends NotificationService {
        final List<Alert> alerts = new ArrayList<>();

        @Override
        void sendAlert(String accountNumber, String subject, String message) {
            alerts.add(new Alert(accountNumber, subject, message));
        }
    }

    private RecordingNotificationService service;

    @BeforeEach
    void setUp() {
        service = new RecordingNotificationService();
    }

    private Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    // ---------- transaction.otp.generated ----------

    @Test
    void otpGenerated_sendsVerificationAlertWithOtpAndReason() {
        service.consumeNotification(map(
                "accountNumber", "111111111111",
                "otp", "907143",
                "transactionId", "tx-1",
                "amount", "300.00",
                "reason", "High value transfer"));

        assertThat(service.alerts).hasSize(1);
        Alert alert = service.alerts.get(0);
        assertThat(alert.account()).isEqualTo("111111111111");
        assertThat(alert.subject()).isEqualTo("TRANSACTION VERIFICATION REQUIRED");
        assertThat(alert.message())
                .contains("High value transfer")
                .contains("300.00")
                .contains("907143");
    }

    // ---------- transaction.completed ----------

    @Test
    void transactionCompleted_sendsDebitAndCreditAlerts() {
        service.consumeTransactionCompleted(map(
                "senderAccountNumber", "111111111111",
                "receiverAccountNumber", "222222222222",
                "amount", "300.00",
                "transactionId", "tx-1"));

        assertThat(service.alerts).hasSize(2);
        assertThat(service.alerts.get(0))
                .isEqualTo(new Alert("111111111111", "DEBIT ALERT", "300.00 debited from 111111111111"));
        assertThat(service.alerts.get(1))
                .isEqualTo(new Alert("222222222222", "CREDIT ALERT", "300.00 credited from account 222222222222"));
    }

    @Test
    void transactionCompleted_missingAmount_isSwallowedAndSendsNothing() {
        service.consumeTransactionCompleted(map(
                "senderAccountNumber", "111111111111",
                "receiverAccountNumber", "222222222222",
                "transactionId", "tx-1"));

        assertThat(service.alerts).isEmpty();
    }

    // ---------- fraud.detected ----------

    @Test
    void fraudDetected_sendsBlockedAlertWithReason() {
        service.consumeFraudDetection(map(
                "accountNumber", "111111111111",
                "reason", "Wrong OTP entered"));

        assertThat(service.alerts).hasSize(1);
        Alert alert = service.alerts.get(0);
        assertThat(alert.account()).isEqualTo("111111111111");
        assertThat(alert.subject()).isEqualTo("SUSPICIOUS ACTIVITY DETECTED");
        assertThat(alert.message())
                .contains("111111111111")
                .contains("has been blocked")
                .contains("Wrong OTP entered");
    }

    // ---------- transaction.failed ----------

    @Test
    void transactionFailed_sendsFailureAlert() {
        service.consumeTransactionFailed(map(
                "accountNumber", "111111111111",
                "amount", "300.00",
                "reason", "Insufficient funds"));

        assertThat(service.alerts).hasSize(1);
        Alert alert = service.alerts.get(0);
        assertThat(alert.subject()).isEqualTo("TRANSACTION FAILED");
        assertThat(alert.message()).contains("300.00").contains("Insufficient funds");
    }

    // ---------- transaction.refunded ----------

    @Test
    void transactionRefunded_sendsRefundAlertWithAmountAndAccount() {
        service.consumeTransactionRefunded(map(
                "accountNumber", "111111111111",
                "amount", "300.00",
                "reason", "Wrong OTP entered"));

        assertThat(service.alerts).hasSize(1);
        Alert alert = service.alerts.get(0);
        assertThat(alert.account()).isEqualTo("111111111111");
        assertThat(alert.subject()).isEqualTo("REFUND PROCESSED");
        assertThat(alert.message())
                .contains("cancelled")
                .contains("Wrong OTP entered")
                .contains("300.00 has been refunded to account 111111111111");
    }

    // ---------- payment.completed ----------

    @Test
    void paymentCompleted_sendsSuccessAlertWithRazorpayId() {
        service.consumePaymentCompleted(map(
                "accountNumber", "111111111111",
                "amount", "499.00",
                "razorpayPaymentId", "pay_ABC123"));

        assertThat(service.alerts).hasSize(1);
        Alert alert = service.alerts.get(0);
        assertThat(alert.subject()).isEqualTo("PAYMENT SUCCESSFUL");
        assertThat(alert.message()).contains("499.00").contains("pay_ABC123");
    }

    // ---------- payment.failed ----------

    @Test
    void paymentFailed_sendsFailureAlert() {
        service.consumePaymentFailed(map(
                "accountNumber", "111111111111",
                "amount", "499.00"));

        assertThat(service.alerts).hasSize(1);
        Alert alert = service.alerts.get(0);
        assertThat(alert.subject()).isEqualTo("PAYMENT FAILED");
        assertThat(alert.message()).contains("499.00");
    }

    // ---------- error handling ----------

    @Test
    void listenerWithNullPayloadFields_doesNotThrow() {
        service.consumeFraudDetection(map());
        service.consumeTransactionFailed(map());
        service.consumeTransactionRefunded(map());
        service.consumePaymentCompleted(map());
        service.consumePaymentFailed(map());
        service.consumeNotification(map());

        // Missing fields become "null" strings in messages; the listener must still not throw.
    }
}

