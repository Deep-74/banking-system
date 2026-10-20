package com.banking.accountservice.controller;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RequestMapping("/api/v1/accounts")
@RestController
@Slf4j
@RequiredArgsConstructor
public class AccountController
{
   private final AccountService accountService;

   @PostMapping
   public ResponseEntity<AccountResponse>createAccount(@Valid @RequestBody CreateAccountRequest createAccountRequest)
   {
      // Implementation for creating an account
      return ResponseEntity.status(HttpStatus.CREATED).body(accountService.createAccount(createAccountRequest));
   }

   @GetMapping("/{accountNumber}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable String accountNumber)
    {
        // Implementation for retrieving an account by account number
        return ResponseEntity.ok(accountService.getAccount(accountNumber));
    }

    @GetMapping("/{accountNumber}/balance")
    public ResponseEntity<BigDecimal> getBalance(@PathVariable String accountNumber)
    {
        // Implementation for retrieving an account by account number
        return ResponseEntity.ok(accountService.getBalance(accountNumber));
    }

    @PutMapping("/{accountNumber}/block")
    public ResponseEntity<?> blockAccount(@PathVariable String accountNumber)
    {
        // Implementation for blocking an account
        accountService.blockAccount(accountNumber);
        return ResponseEntity.ok("AccoutBlock successfully");
    }


    /**
     * SAGA STEP 1: Deduct Balance
     * Called by Transaction Service when transfer is initiated.
     */

    @PutMapping("/{accountNumber}/deduct")
    public ResponseEntity<String> deductBalance(@PathVariable String accountNumber, @RequestParam BigDecimal amount)
    {
        // Implementation for deducting balance
        accountService.deductBalance(accountNumber, amount);
        return ResponseEntity.ok("Balance deducted successfully");
    }

    /**
     * SAGA STEP 4: Compensating transaction endpoint
     * Called by Transaction Service in TWO SCENARIOS:
     * 1. Fraud detected -> refund Sender (undo step 1)
     * 2. Transaction completed --> Credit receiver
     */


    @PutMapping("/{accountNumber}/credit")
    public ResponseEntity<String> creditBalance(@PathVariable String accountNumber, @RequestParam BigDecimal amount)
    {
        // Implementation for crediting balance
        accountService.creditBalance(accountNumber, amount);
        return ResponseEntity.ok("Balance credited successfully");
    }


}
