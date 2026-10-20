package com.banking.accountservice.dto;

import com.banking.accountservice.entity.AccountType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;


@Data
public class CreateAccountRequest
{

    @NotBlank(message = "accountHolderName is required")
    private String accountHolderName;

    @NotBlank(message = "email is required")
    @Email(message = "invalid email format")
    private String email;

    @NotBlank(message = "phone n.o is required")
    private String phone;

    @NotNull(message = "accountType is required")
    private AccountType accountType;

    @NotNull(message = "initialDeposit is required")
    @Positive(message = "initialDeposit must be positive")
    private BigDecimal initialDeposit;
}
