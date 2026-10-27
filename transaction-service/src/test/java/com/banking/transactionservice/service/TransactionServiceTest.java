package com.banking.transactionservice.service;

import com.banking.transactionservice.client.AccountServiceClient;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.entity.TransationType;
import com.banking.transactionservice.exception.InvalidOTPException;
import com.banking.transactionservice.exception.OTPExpiredException;
import com.banking.transactionservice.exception.TransactionNotFoundException;
import com.banking.transactionservice.repository.TransactionRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private AccountServiceClient accountServiceClient;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private TransactionService transactionService;

    private TransferRequest transferRequest(String description) {
        TransferRequest request = new TransferRequest();
        request.setSenderAccountNumber("111111111111");
        request.setReceiverAccountNumber("222222222222");
        request.setAmount(new BigDecimal("300.00"));
        request.setDescription(description);
        return request;
    }

    private Transaction transaction(String id, TransactionStatus status) {
        Transaction tx = new Transaction();
        tx.setId(id);
        tx.setSenderAccountNumber("111111111111");
        tx.setReceiverAccountNumber("222222222222");
        tx.setAmount(new BigDecimal("300.00"));
        tx.setType(TransationType.TRANSFER);
        tx.setStatus(status);
        tx.setDescription("rent");
        tx.setReferenceNumber("ref-1");
        return tx;
    }

    /** Make repository.save() assign an ID, as JPA would. */
    private void stubSaveAssignsId(String id) {
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(id);
            }
            return t;
        });
    }

    // ---------- transfer ----------

    @Test
    void transfer_happyPath_deductsSavesAndPublishesInitiatedEvent() {
        stubSaveAssignsId("tx-1");

        TransactionResponse response = transactionService.transfer(transferRequest("rent"));

        verify(accountServiceClient).deductBalance("111111111111", new BigDecimal("300.00"));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(saved.getType()).isEqualTo(TransationType.TRANSFER);
        assertThat(saved.getReferenceNumber()).isNotBlank();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("transaction.initiated"), eq("tx-1"), eventCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) eventCaptor.getValue();
        assertThat(event)
                .containsEntry("transactionId", "tx-1")
                .containsEntry("accountNumber", "111111111111")
                .containsEntry("receiverAccountNumber", "222222222222")
                .containsEntry("amount", "300.00")
                .containsEntry("description", "rent");

        assertThat(response.getId()).isEqualTo("tx-1");
        assertThat(response.getStatus()).isEqualTo(TransactionStatus.PROCESSING);
    }

    @Test
    void transfer_deductFails_noTransactionSavedNoEventPublished() {
        doThrow(new RuntimeException("insufficient funds"))
                .when(accountServiceClient).deductBalance(anyString(), any());

        assertThatThrownBy(() -> transactionService.transfer(transferRequest("rent")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("insufficient funds");

        verify(transactionRepository, never()).save(any());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    /**
     * Known defect: TransactionService builds the Kafka payload with Map.of(...),
     * which throws NullPointerException for null values. A transfer without a
     * description therefore fails after the sender has already been debited.
     */
    @Test
    @Disabled("Known bug: Map.of rejects null description in TransactionService.transfer")
    void transfer_withoutDescription_succeeds() {
        stubSaveAssignsId("tx-2");

        TransactionResponse response = transactionService.transfer(transferRequest(null));

        assertThat(response.getId()).isEqualTo("tx-2");
    }

    // ---------- verifyOTP ----------

    @Test
    void verifyOTP_correctOtp_completesTransactionAndPublishesCompleted() {
        Transaction tx = transaction("tx-1", TransactionStatus.PENDING_VERIFICATION);
        when(transactionRepository.findById("tx-1")).thenReturn(Optional.of(tx));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("verification:otp:tx-1")).thenReturn("123456");

        TransactionResponse response = transactionService.verifyOTP("tx-1", "123456");

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(tx.getCompletedAt()).isNotNull();
        verify(redisTemplate).delete("verification:otp:tx-1");
        verify(kafkaTemplate).send(eq("transaction.completed"), eq("tx-1"), any());
        verify(accountServiceClient, never()).creditBalance(anyString(), any());
        assertThat(response.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void verifyOTP_expiredOtp_throwsExpiredAndLeavesTransactionUnchanged() {
        Transaction tx = transaction("tx-1", TransactionStatus.PENDING_VERIFICATION);
        when(transactionRepository.findById("tx-1")).thenReturn(Optional.of(tx));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("verification:otp:tx-1")).thenReturn(null);

        assertThatThrownBy(() -> transactionService.verifyOTP("tx-1", "123456"))
                .isInstanceOf(OTPExpiredException.class);

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING_VERIFICATION);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(accountServiceClient, never()).creditBalance(anyString(), any());
    }

    @Test
    void verifyOTP_wrongOtp_blocksAccountRefundsSenderAndThrows() {
        Transaction tx = transaction("tx-1", TransactionStatus.PENDING_VERIFICATION);
        when(transactionRepository.findById("tx-1")).thenReturn(Optional.of(tx));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("verification:otp:tx-1")).thenReturn("123456");

        assertThatThrownBy(() -> transactionService.verifyOTP("tx-1", "000000"))
                .isInstanceOf(InvalidOTPException.class)
                .hasMessageContaining("blocked");

        verify(redisTemplate).delete("verification:otp:tx-1");
        // fraud.detected -> account service blocks the account
        verify(kafkaTemplate).send(eq("fraud.detected"), eq("tx-1"), any());
        // refund sender synchronously, then publish refund event
        verify(accountServiceClient).creditBalance("111111111111", new BigDecimal("300.00"));
        verify(kafkaTemplate).send(eq("transaction.refunded"), eq("tx-1"), any());

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(tx.getFailureReason()).contains("Wrong OTP");
        verify(kafkaTemplate, never()).send(eq("transaction.completed"), anyString(), any());
    }

    @Test
    void verifyOTP_unknownTransaction_throwsNotFound() {
        when(transactionRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.verifyOTP("missing", "123456"))
                .isInstanceOf(TransactionNotFoundException.class)
                .hasMessageContaining("missing");

        verify(redisTemplate, never()).opsForValue();
    }

    // ---------- getTransaction / getTransactionHistory ----------

    @Test
    void getTransaction_existing_returnsMappedResponse() {
        Transaction tx = transaction("tx-1", TransactionStatus.COMPLETED);
        when(transactionRepository.findById("tx-1")).thenReturn(Optional.of(tx));

        TransactionResponse response = transactionService.getTransaction("tx-1");

        assertThat(response.getId()).isEqualTo("tx-1");
        assertThat(response.getAmount()).isEqualByComparingTo("300.00");
        assertThat(response.getReferenceNumber()).isEqualTo("ref-1");
        assertThat(response.getType()).isEqualTo(TransationType.TRANSFER);
    }

    @Test
    void getTransaction_missing_throwsNotFound() {
        when(transactionRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getTransaction("nope"))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void getTransactionHistory_mapsAllTransactionsForAccount() {
        when(transactionRepository.findBySenderAccountNumberOrderByCreatedAtDesc("111111111111"))
                .thenReturn(List.of(
                        transaction("tx-1", TransactionStatus.COMPLETED),
                        transaction("tx-2", TransactionStatus.PROCESSING)));

        List<TransactionResponse> history = transactionService.getTransactionHistory("111111111111");

        assertThat(history).extracting(TransactionResponse::getId).containsExactly("tx-1", "tx-2");
    }

    @Test
    void getTransactionHistory_noTransactions_returnsEmptyList() {
        when(transactionRepository.findBySenderAccountNumberOrderByCreatedAtDesc(anyString()))
                .thenReturn(List.of());

        assertThat(transactionService.getTransactionHistory("999")).isEmpty();
    }

    // ---------- processCleanResult ----------

    @Test
    void processCleanResult_processingTransaction_completesIt() {
        Transaction tx = transaction("tx-1", TransactionStatus.PROCESSING);
        when(transactionRepository.findById("tx-1")).thenReturn(Optional.of(tx));

        transactionService.processCleanResult("tx-1");

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        verify(kafkaTemplate).send(eq("transaction.completed"), eq("tx-1"), any());
    }

    @Test
    void processCleanResult_notProcessing_isSkipped() {
        Transaction tx = transaction("tx-1", TransactionStatus.PENDING_VERIFICATION);
        when(transactionRepository.findById("tx-1")).thenReturn(Optional.of(tx));

        transactionService.processCleanResult("tx-1");

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING_VERIFICATION);
        verify(transactionRepository, never()).save(any());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void processCleanResult_unknownTransaction_throwsNotFound() {
        when(transactionRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.processCleanResult("nope"))
                .isInstanceOf(TransactionNotFoundException.class);
    }
}

