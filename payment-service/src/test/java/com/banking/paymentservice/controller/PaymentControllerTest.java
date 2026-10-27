package com.banking.paymentservice.controller;

import com.banking.paymentservice.dto.PaymentOrderResponse;
import com.banking.paymentservice.exception.GlobalExceptionHandler;
import com.banking.paymentservice.exception.PaymentNotFoundException;
import com.banking.paymentservice.exception.RazorpayException;
import com.banking.paymentservice.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock
    private PaymentService paymentService;

    private MockMvc mockMvc;

    private static final String VALID_ORDER = """
            {
              "accountNumber": "111111111111",
              "amount": 499.00,
              "description": "bill"
            }
            """;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PaymentController(paymentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ---------- create-order ----------

    @Test
    void createOrder_validRequest_returns201WithOrder() throws Exception {
        PaymentOrderResponse response = new PaymentOrderResponse(
                "pay-db-1", "order_ABC", new BigDecimal("499.00"), "USD/INR", "CREATED", "rzp_test_key");
        when(paymentService.createPaymentOrder(any())).thenReturn(response);

        mockMvc.perform(post("/api/payments/create-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_ORDER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.razorpayOrderId").value("order_ABC"))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void createOrder_missingAccount_returns400() throws Exception {
        String body = VALID_ORDER.replace("111111111111", "");

        mockMvc.perform(post("/api/payments/create-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void createOrder_nonPositiveAmount_returns400() throws Exception {
        String body = VALID_ORDER.replace("499.00", "0");

        mockMvc.perform(post("/api/payments/create-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        verify(paymentService, never()).createPaymentOrder(any());
    }

    @Test
    void createOrder_razorpayFailure_returns400WithRazorpayError() throws Exception {
        when(paymentService.createPaymentOrder(any()))
                .thenThrow(new RazorpayException("Failed to create payment order with Razorpay"));

        mockMvc.perform(post("/api/payments/create-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_ORDER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("RAZORPAY_ERROR"));
    }

    // ---------- webhook ----------

    @Test
    void webhook_returns200AndDelegatesToService() throws Exception {
        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"payment.captured\"}"))
                .andExpect(status().isOk());

        verify(paymentService).handleWebhook(anyMap());
    }

    // ---------- exception handler ----------

    @Test
    void paymentNotFound_returns404() throws Exception {
        doThrow(new PaymentNotFoundException("missing"))
                .when(paymentService).handleWebhook(anyMap());

        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"payment.captured\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_FOUND"));
    }
}

