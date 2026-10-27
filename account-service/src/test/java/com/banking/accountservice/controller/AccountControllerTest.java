package com.banking.accountservice.controller;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.entity.AccountStatus;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.exception.AccountAlreadyExistsException;
import com.banking.accountservice.exception.AccountNotFoundException;
import com.banking.accountservice.exception.GlobalExceptionHandler;
import com.banking.accountservice.exception.InsufficientBalanceException;
import com.banking.accountservice.service.AccountService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    @Mock
    private AccountService accountService;

    private MockMvc mockMvc;

    private static final String VALID_BODY = """
            {
              "accountHolderName": "Jane Doe",
              "email": "jane@example.com",
              "phone": "9999999999",
              "accountType": "SAVING",
              "initialDeposit": 500
            }
            """;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AccountController(accountService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private AccountResponse sampleResponse() {
        AccountResponse response = new AccountResponse();
        response.setId("id-1");
        response.setAccountNumber("123456789012");
        response.setAccountHolderName("Jane Doe");
        response.setEmail("jane@example.com");
        response.setPhone("9999999999");
        response.setAccountType(AccountType.SAVING);
        response.setStatus(AccountStatus.ACTIVE);
        response.setBalance(new BigDecimal("500"));
        response.setDailyTransactionLimit(new BigDecimal("1000"));
        return response;
    }

    @Test
    void createAccount_validRequest_returns201() throws Exception {
        when(accountService.createAccount(any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountNumber").value("123456789012"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createAccount_invalidEmail_returns400() throws Exception {
        String body = VALID_BODY.replace("jane@example.com", "not-an-email");

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        verify(accountService, org.mockito.Mockito.never()).createAccount(any());
    }

    @Test
    void createAccount_missingName_returns400() throws Exception {
        String body = VALID_BODY.replace("\"accountHolderName\": \"Jane Doe\",", "");

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createAccount_duplicateEmail_returns409() throws Exception {
        when(accountService.createAccount(any()))
                .thenThrow(new AccountAlreadyExistsException("exists"));

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_ALREADY_EXISTS"));
    }

    @Test
    void getAccount_existing_returns200() throws Exception {
        when(accountService.getAccount("123456789012")).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/accounts/123456789012"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value("123456789012"));
    }

    @Test
    void getAccount_missing_returns404() throws Exception {
        when(accountService.getAccount("000"))
                .thenThrow(new AccountNotFoundException("Account not found"));

        mockMvc.perform(get("/api/v1/accounts/000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void getBalance_returns200WithBalance() throws Exception {
        when(accountService.getBalance("123456789012")).thenReturn(new BigDecimal("500"));

        mockMvc.perform(get("/api/v1/accounts/123456789012/balance"))
                .andExpect(status().isOk())
                .andExpect(content().string("500"));
    }

    @Test
    void blockAccount_returns200() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/123456789012/block"))
                .andExpect(status().isOk());

        verify(accountService).blockAccount("123456789012");
    }

    @Test
    void deductBalance_returns200() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/123456789012/deduct").param("amount", "100"))
                .andExpect(status().isOk())
                .andExpect(content().string("Balance deducted successfully"));

        verify(accountService).deductBalance("123456789012", new BigDecimal("100"));
    }

    @Test
    void deductBalance_insufficientFunds_returns400() throws Exception {
        doThrow(new InsufficientBalanceException("low"))
                .when(accountService).deductBalance(anyString(), any());

        mockMvc.perform(put("/api/v1/accounts/123456789012/deduct").param("amount", "9999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    void creditBalance_returns200() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/123456789012/credit").param("amount", "50"))
                .andExpect(status().isOk())
                .andExpect(content().string("Balance credited successfully"));

        verify(accountService).creditBalance("123456789012", new BigDecimal("50"));
    }
}

