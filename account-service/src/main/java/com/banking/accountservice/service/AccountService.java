package com.banking.accountservice.service;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.entity.Account;
import com.banking.accountservice.entity.AccountStatus;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.exception.AccountNotFoundException;
import com.banking.accountservice.exception.InsufficientBalanceException;
import com.banking.accountservice.exception.AccountBlockedException;
import com.banking.accountservice.exception.AccountAlreadyExistsException;
import com.banking.accountservice.repository.AccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;

@Service @Slf4j
public class AccountService
{
    private final AccountRepository accountRepository;
    private  static SecureRandom securerandom = new SecureRandom();

    AccountService(AccountRepository accountRepository)
    {
        this.accountRepository = accountRepository;
    }

    public AccountResponse createAccount(CreateAccountRequest createAccountRequest)
    {
        // Implementation for creating an account
        log.info("Creating account for: {}", createAccountRequest.getEmail());

        if(accountRepository.existsByEmail(createAccountRequest.getEmail()))
        {
            log.error("Account with email {} already exists", createAccountRequest.getEmail());
            throw new AccountAlreadyExistsException("Account with email " + createAccountRequest.getEmail() + " already exists");
        }

        Account account = new Account();
        account.setAccountHolderName(createAccountRequest.getAccountHolderName());
        account.setEmail(createAccountRequest.getEmail());
        account.setPhone(createAccountRequest.getPhone());
        account.setAccountType(createAccountRequest.getAccountType());
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(createAccountRequest.getInitialDeposit());
        account.setAccountNumber(generateAccountNumber());
        account.setDailyTransactionLimit(
                createAccountRequest.getAccountType() == AccountType.SAVING ? new BigDecimal("1000") : new BigDecimal("5000")
        );

        Account savedAccount = accountRepository.save(account);
        log.info("Account created with ID: {}", savedAccount.getAccountNumber());

        return mapToResponse(savedAccount);
    }


/*BlockAccount called by Fraud detection Service via kafka

 */
    public void blockAccount(String accountNumber)
    {
        log.info("Fraud detected, blocking account number: {}",accountNumber);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> {
                    log.error("Account not found while attempting to block: {}", accountNumber);
                    return new AccountNotFoundException("Account not found with account number: " + accountNumber);
                });
        account.setStatus(AccountStatus.BLOCKED);

        accountRepository.save(account);
    }

    /*
    *  GET ACCOUNT BY ACCOUNT NUMBER
    *
    */

    public AccountResponse getAccount(String accountNumber)
    {
        log.info("Fetching account with account number: {}", accountNumber);

        Account account = accountRepository.findByAccountNumber(accountNumber)
                        .orElseThrow(() -> {log.error("Account with account number {} not found", accountNumber);
                        return new AccountNotFoundException("Account not found");});


        return mapToResponse(account);
    }

    /*
    * GET ACCOUNT BALANCE BY ACCOUNT NUMBER
     */

    public BigDecimal getBalance(String accountNumber)
    {
        log.info("Fetching account with account number: {}", accountNumber);

        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> {log.error("Account with account number {} not found", accountNumber);
                 return new AccountNotFoundException("Account not found with account number: " + accountNumber);});

        return account.getBalance();
    }

    /*
    * Deduct balance from sender account
    * Called by Transaction Service
     */

    public void deductBalance(String accountNumber,BigDecimal amount )
    {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> {log.error("Account with account number {} not found", accountNumber);
                    return new AccountNotFoundException("Account not found with account number: " + accountNumber);});


        if(AccountStatus.BLOCKED.equals(account.getStatus()))
        {
            log.error("Account with account number {} is blocked", accountNumber);
            throw new AccountBlockedException("Account is blocked for account number: " + accountNumber);
        }

        if(account.getBalance().compareTo(amount) < 0)
        {
            log.error("Insufficient balance in account number {}", accountNumber);
            throw new InsufficientBalanceException("Insufficient balance in account " + accountNumber + ". Required: " + amount + ", Available: " + account.getBalance());
        }

        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);
    }


//
//    public void creditBalance(String accountNumber, BigDecimal amount)
//    {
//        Account account = accountRepository.findByAccountNumber(accountNumber)
//                .orElseThrow(() -> {log.error("Account with account number {} not found", accountNumber);
//                    return new RuntimeException("Account not found");});
//
//        if(! AccountStatus.BLOCKED.equals(account.getStatus()))
//        {
//            log.error("Account with account number {} is non Active", accountNumber);
//            throw new RuntimeException("Account is blocked");
//        }
//
//        account.setBalance(account.getBalance().add(amount));
//        accountRepository.save(account);
//    }




    /* Credit Balance
    * Called by Transaction Service via kafka
     */

    public void creditBalance(String accountNumber,BigDecimal balance)
    {

        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> {log.error("Account with account number {} not found", accountNumber);
                    return new AccountNotFoundException("Account not found with account number: " + accountNumber);});

        if(! AccountStatus.BLOCKED.equals(account.getStatus()))
        {
            log.error("Account with account number {} is blocked", accountNumber);
            throw new AccountBlockedException("Account is blocked for account number: " + accountNumber);
        }

        account.setBalance(account.getBalance().add(balance));
        accountRepository.save(account);

    }

    //generate a unique 12 digit account number
    private String generateAccountNumber()
    {
        String accountNumber = "";

        do{
            long number = securerandom.nextLong(100000000000L, 999999999999L);
            accountNumber = String.format("%012d", number);
        }
        while (accountRepository.existsByAccountNumber(accountNumber));

        return accountNumber;
    }

    private AccountResponse mapToResponse (Account account)
    {
        AccountResponse response  = new AccountResponse();

        response.setId(account.getId());
        response.setAccountNumber(account.getAccountNumber());
        response.setAccountHolderName(account.getAccountHolderName());
        response.setEmail(account.getEmail());
        response. setPhone(account.getPhone());
        response.setAccountType(account.getAccountType());
        response.setStatus(account.getStatus());
        response.setBalance(account.getBalance());
        response. setDailyTransactionLimit(account.getDailyTransactionLimit());
        response.setCreatedAt(account.getCreatedAt());
        return response;
    }
}
