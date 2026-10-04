package com.banking.accountservice.entity;



import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import jakarta.persistence.GenerationType;

import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
@Data
public class Account
{
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String accountNumber;
    private String accountHolderNumber;
    private String email;

    private String phone;

    @Enumerated(EnumType.STRING)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    private AccountStatus accountStatus;

    private BigDecimal balance;

    private BigDecimal dailyTransactionLimit;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
