
package com.banking.paymentservice.service;

import com.banking.paymentservice.dto.CreatePaymentRequest;
import com.banking.paymentservice.dto.PaymentOrderResponse;
import com.banking.paymentservice.entity.Payment;
import com.banking.paymentservice.entity.PaymentStatus;
import com.banking.paymentservice.repository.PaymentRepository;
import com.banking.paymentservice.exception.PaymentNotFoundException;
import com.banking.paymentservice.exception.RazorpayException;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor @Service @Slf4j
public class PaymentService
{
    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String,Object> kafkaTemplate;

    @Value("${razorpay.key-id}")
    private String keyId;

    @Value("${razorpay.key-secret}")
    private String keySecret;

    private static final String PAYMENT_COMPLETED_TOPIC = "payment.completed";
    private static final String PAYMENT_FAILED_TOPIC = "payment.failed";

    /**
     * Creating Razorpay Payment order
     *
     * FLOW :
     * 1. Create order in razorpay
     * 2. Save Payment record in DB
     * 3. Return order details to frontend
     * 4. Frontend shows Razorpay checkout
     * 5. User Pays
     * 6. Razorpay calls webhook
     * @Param request
     * @return
     */

    public PaymentOrderResponse createPaymentOrder(CreatePaymentRequest request) throws RazorpayException
    {
        log.info("Creating payment order for account: {} amount{}", request.getAccountNumber(), request.getAmount());

        try {
            RazorpayClient razorpayClient = new RazorpayClient(keyId, keySecret);

            //  Converted amount to paise
            int convertedAmount = request.getAmount().multiply(BigDecimal.valueOf(100)).intValue(); // Convert to paise


            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount", convertedAmount); // amount in paise
            orderRequest.put("currency", "INR");
            orderRequest.put("receipt", "rcpt_" + UUID.randomUUID().toString().replace("-","").substring(0,10)); // Unique receipt id

            Order razorpayOrder = razorpayClient.orders.create(orderRequest);

            log.info("Razorpay order created: {}", razorpayOrder.get("id").toString());


            //Save payment record in DB

            Payment payment = new Payment();
            payment.setAccountNumber(request.getAccountNumber());
            payment.setAmount(request.getAmount());
            payment.setRazorpayOrderId(razorpayOrder.get("id").toString());
            payment.setStatus(PaymentStatus.CREATED);
            payment.setCurrency("USD/INR");
            payment.setDescription(request.getDescription());

            Payment savedPayment = paymentRepository.save(payment);



            return new PaymentOrderResponse(savedPayment.getId(),razorpayOrder.get("id").toString(),
                    request.getAmount(), "USD/INR", "CREATED",keyId);

        } catch (com.razorpay.RazorpayException e) {
            log.error("Razorpay API error while creating payment order: {}", e.getMessage());
            throw new RazorpayException("Failed to create payment order with Razorpay. Error: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error while creating payment order: {}", e.getMessage());
            throw new RazorpayException("Unexpected error while creating payment order: " + e.getMessage(), e);
        }
    }

    public void handleWebhook(Map<String,Object> payload)
    {
        log.info("Received webhook payload: {}", payload.get("event"));

        String event = (String) payload.get("event");

        if("payment.captured".equals(event))
        {
            handlePaymentSuccess(payload);
        }
        else if("payment.failed".equals(event))
        {
            handlePaymentFailure(payload);
        }
        else
        {
            log.warn("Unhandled webhook event: {}", event);
        }

    }


    private void handlePaymentSuccess(Map<String,Object> payload)
    {

        try{
            Map<String,Object> paymentData = extractPaymentData(payload);
            String orderId = (String) paymentData.get("order_id");
            String paymentId = (String) paymentData.get("id");

            Payment payment = paymentRepository.findByRazorpayOrderId(orderId)
                    .orElseThrow(() -> {
                        log.error("Payment not found for Razorpay order ID: {}", orderId);
                        return new PaymentNotFoundException("Payment not found for Razorpay order ID: " + orderId);
                    });

            payment.setRazorpayPaymentId(paymentId);
            payment.setStatus(PaymentStatus.COMPLETED);
            paymentRepository.save(payment);

            //Publish payment event to kafka

            Map<String,Object> eventPayload = Map.of(
                    "paymentId", payment.getId(),
                    "accountNumber", payment.getAccountNumber(),
                    "amount", payment.getAmount(),
                    "status", payment.getStatus().toString(),
                    "razorpayPaymentId", payment.getRazorpayPaymentId()
            );

            kafkaTemplate.send(PAYMENT_COMPLETED_TOPIC,payment.getId(),eventPayload);

            log.info("Payment Completed:{}",payment.getId());

        }
        catch (PaymentNotFoundException e) {
            log.error("Payment not found error in handlePaymentSuccess: {}", e.getMessage());
            // Don't re-throw here as this is called from webhook handler
        }
        catch (Exception e)
        {
            log.error("Error handling payment success : {}", e.getMessage(), e);
        }
    }

    private void handlePaymentFailure (Map<String,Object> payload)
    {
        try {
            Map<String, Object> paymentData = extractPaymentData(payload);
            String orderId = (String) paymentData.get("order_id");
            String paymentId = (String) paymentData.get("id");

            Payment payment = paymentRepository.findByRazorpayOrderId(orderId)
                    .orElseThrow(() -> {
                        log.error("Payment not found for Razorpay order ID: {}", orderId);
                        return new PaymentNotFoundException("Payment not found for Razorpay order ID: " + orderId);
                    });

            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Payment failed at Razorpay");
            paymentRepository.save(payment);

            Map<String,Object> eventPayload = Map.of(
                    "paymentId", payment.getId(),
                    "orderId", payment.getAccountNumber(),
                    "status", payment.getAmount(),
                    "reason", "Payment failed at Razorpay"
            );

            kafkaTemplate.send(PAYMENT_FAILED_TOPIC,payment.getId(),eventPayload);

            log.warn("Payment Failed:{}",payment.getId());

        }
        catch (PaymentNotFoundException e) {
            log.error("Payment not found error in handlePaymentFailure: {}", e.getMessage());
            // Don't re-throw here as this is called from webhook handler
        }
        catch (Exception e)
        {
            log.error("Error handling payment failure : {}", e.getMessage(), e);
        }
    }

        private Map<String,Object> extractPaymentData(Map<String,Object> payload)
        {
            Map<String,Object> paymentEntity = (Map<String,Object>) payload.get("payload");
            Map<String,Object> paymentWrapper = (Map<String,Object>) paymentEntity.get("payment");
            Map<String,Object> paymentDetails = (Map<String,Object>) paymentWrapper.get("entity");

            return paymentDetails;
        }




}
