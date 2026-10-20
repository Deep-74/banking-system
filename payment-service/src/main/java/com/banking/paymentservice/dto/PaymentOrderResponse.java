package com.banking.paymentservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data @AllArgsConstructor
public class PaymentOrderResponse
{
    private String paymentId;
    private String razorpayOrderId;
    private BigDecimal amount;
    private String concurrency;
    private String status;

    private String razorPayKeyId;

}
