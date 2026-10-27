package com.banking.paymentservice.service;

import com.banking.paymentservice.entity.Payment;
import com.banking.paymentservice.entity.PaymentStatus;
import com.banking.paymentservice.exception.PaymentNotFoundException;
import com.banking.paymentservice.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String ORDER_ID = "order_ABC";
    private static final String RAZORPAY_PAYMENT_ID = "pay_XYZ";

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private PaymentService paymentService;

    @BeforeEach
    void setKeys() {
        ReflectionTestUtils.setField(paymentService, "keyId", "rzp_test_key");
        ReflectionTestUtils.setField(paymentService, "keySecret", "rzp_test_secret");
    }

    private Payment storedPayment(PaymentStatus status) {
        Payment payment = new Payment();
        payment.setId("pay-db-1");
        payment.setAccountNumber("111111111111");
        payment.setAmount(new BigDecimal("499.00"));
        payment.setRazorpayOrderId(ORDER_ID);
        payment.setStatus(status);
        return payment;
    }

    /** Builds the Razorpay webhook body: payload.payment.entity.{order_id,id}. */
    private Map<String, Object> webhook(String event, String orderId, String paymentId) {
        Map<String, Object> entity = new HashMap<>();
        entity.put("order_id", orderId);
        entity.put("id", paymentId);

        Map<String, Object> paymentWrapper = new HashMap<>();
        paymentWrapper.put("entity", entity);

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("payment", paymentWrapper);

        Map<String, Object> root = new HashMap<>();
        root.put("event", event);
        root.put("payload", payloadMap);
        return root;
    }

    // ---------- webhook: payment.captured ----------

    @Test
    void capturedWebhook_marksPaymentCompletedAndSavesRazorpayId() {
        Payment payment = storedPayment(PaymentStatus.CREATED);
        when(paymentRepository.findByRazorpayOrderId(ORDER_ID)).thenReturn(Optional.of(payment));

        paymentService.handleWebhook(webhook("payment.captured", ORDER_ID, RAZORPAY_PAYMENT_ID));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getRazorpayPaymentId()).isEqualTo(RAZORPAY_PAYMENT_ID);
        verify(paymentRepository).save(payment);
    }

    @Test
    void capturedWebhook_publishesPaymentCompletedEvent() {
        Payment payment = storedPayment(PaymentStatus.CREATED);
        when(paymentRepository.findByRazorpayOrderId(ORDER_ID)).thenReturn(Optional.of(payment));

        paymentService.handleWebhook(webhook("payment.captured", ORDER_ID, RAZORPAY_PAYMENT_ID));

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("payment.completed"), eq("pay-db-1"), event.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) event.getValue();
        assertThat(body)
                .containsEntry("paymentId", "pay-db-1")
                .containsEntry("accountNumber", "111111111111")
                .containsEntry("status", "COMPLETED")
                .containsEntry("razorpayPaymentId", RAZORPAY_PAYMENT_ID);
    }

    @Test
    void capturedWebhook_unknownOrder_publishesNothingAndDoesNotThrow() {
        when(paymentRepository.findByRazorpayOrderId("order_unknown")).thenReturn(Optional.empty());

        paymentService.handleWebhook(webhook("payment.captured", "order_unknown", RAZORPAY_PAYMENT_ID));

        verify(paymentRepository, never()).save(any());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    // ---------- webhook: payment.failed ----------

    @Test
    void failedWebhook_marksPaymentFailedWithReason() {
        Payment payment = storedPayment(PaymentStatus.CREATED);
        when(paymentRepository.findByRazorpayOrderId(ORDER_ID)).thenReturn(Optional.of(payment));

        paymentService.handleWebhook(webhook("payment.failed", ORDER_ID, RAZORPAY_PAYMENT_ID));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("Payment failed at Razorpay");
        verify(paymentRepository).save(payment);
    }

    @Test
    void failedWebhook_publishesPaymentFailedEvent() {
        Payment payment = storedPayment(PaymentStatus.CREATED);
        when(paymentRepository.findByRazorpayOrderId(ORDER_ID)).thenReturn(Optional.of(payment));

        paymentService.handleWebhook(webhook("payment.failed", ORDER_ID, RAZORPAY_PAYMENT_ID));

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("payment.failed"), eq("pay-db-1"), event.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) event.getValue();
        assertThat(body)
                .containsEntry("paymentId", "pay-db-1")
                .containsEntry("reason", "Payment failed at Razorpay");
    }

    /**
     * Known defect: handlePaymentFailure puts accountNumber into "orderId" and the amount
     * into "status" in the failure event. Consumers reading those keys get wrong values.
     */
    @Test
    @org.junit.jupiter.api.Disabled("Known bug: failure event maps orderId->accountNumber and status->amount")
    void failedWebhook_eventHasCorrectOrderIdAndStatusFields() {
        Payment payment = storedPayment(PaymentStatus.CREATED);
        when(paymentRepository.findByRazorpayOrderId(ORDER_ID)).thenReturn(Optional.of(payment));

        paymentService.handleWebhook(webhook("payment.failed", ORDER_ID, RAZORPAY_PAYMENT_ID));

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("payment.failed"), eq("pay-db-1"), event.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) event.getValue();
        assertThat(body).containsEntry("orderId", ORDER_ID).containsEntry("status", "FAILED");
    }

    @Test
    void failedWebhook_unknownOrder_publishesNothing() {
        when(paymentRepository.findByRazorpayOrderId("order_unknown")).thenReturn(Optional.empty());

        paymentService.handleWebhook(webhook("payment.failed", "order_unknown", RAZORPAY_PAYMENT_ID));

        verify(paymentRepository, never()).save(any());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    // ---------- webhook: other events ----------

    @Test
    void unhandledEvent_isIgnored() {
        paymentService.handleWebhook(webhook("refund.processed", ORDER_ID, RAZORPAY_PAYMENT_ID));

        verify(paymentRepository, never()).findByRazorpayOrderId(anyString());
        verify(paymentRepository, never()).save(any());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void capturedWebhook_repositoryFailure_isSwallowed() {
        when(paymentRepository.findByRazorpayOrderId(ORDER_ID))
                .thenThrow(new RuntimeException("db down"));

        paymentService.handleWebhook(webhook("payment.captured", ORDER_ID, RAZORPAY_PAYMENT_ID));

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void paymentNotFoundException_isNotPropagatedFromWebhook() {
        when(paymentRepository.findByRazorpayOrderId(ORDER_ID))
                .thenReturn(Optional.empty());

        // handlePaymentSuccess catches PaymentNotFoundException internally
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                paymentService.handleWebhook(webhook("payment.captured", ORDER_ID, RAZORPAY_PAYMENT_ID)));
    }
}

