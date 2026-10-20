# Digital Banking System — Architecture & Project Execution Flow

This document provides a comprehensive, end-to-end technical explanation of the **Digital Banking System**, its microservices architecture, asynchronous event-driven communications, data flow diagrams, REST APIs, and infrastructure components.

---

## Table of Contents

1. [System Overview & Architecture](#1-system-overview--architecture)
2. [Technology Stack & Infrastructure](#2-technology-stack--infrastructure)
3. [Microservices Catalog & Ports](#3-microservices-catalog--ports)
4. [Master Kafka Events & Topics Catalog](#4-master-kafka-events--topics-catalog)
5. [In-Memory State & Redis Strategy](#5-in-memory-state--redis-strategy)
6. [End-to-End Execution Flows & Sequence Diagrams](#6-end-to-end-execution-flows--sequence-diagrams)
   - [Flow 1: Account Creation & Management](#flow-1-account-creation--management)
   - [Flow 2: P2P Money Transfer (Saga Pattern)](#flow-2-p2p-money-transfer-saga-pattern)
   - [Flow 3: Real-Time Fraud Detection Engine](#flow-3-real-time-fraud-detection-engine)
   - [Flow 4: OTP Verification & Compensation (Saga Rollback)](#flow-4-otp-verification--compensation-saga-rollback)
   - [Flow 5: Real-Time Multi-Channel Notification Alerts](#flow-5-real-time-multi-channel-notification-alerts)
7. [REST API Endpoint Directory](#7-rest-api-endpoint-directory)
8. [How to Run & Test the Ecosystem](#8-how-to-run--test-the-ecosystem)

---

## 1. System Overview & Architecture

The system is designed as an enterprise-grade, distributed **event-driven banking ecosystem** utilizing the **Saga Pattern** for distributed transactions, **Apache Kafka** for asynchronous decoupling, **Redis** for stateful rate limiting and fraud velocity scoring, and **Spring Cloud Gateway** as the single entry point.

### High-Level Architecture Diagram

```
                              ┌─────────────────────────────┐
                              │  Client / Mobile / WebApp   │
                              └──────────────┬──────────────┘
                                             │
                                   HTTP / JSON
                                             │
                    ┌────────────────────────▼────────────────────────┐
                    │    API Gateway :8080                            │
                    │    Rate Limiter & Routing                       │
                    └────┬──────────┬──────────┬───────────────────────┘
                         │          │          │
        ┌────────────────▼─┐  ┌────▼──────┐  ┌─▼──────────────┐
        │ Account Service  │  │Transaction │  │ Payment Service│
        │     :8081        │  │ Service    │  │    :8083       │
        │ Accounts,        │  │  :8082     │  │ Wallet Mgmt    │
        │ Balance & Limits │  │ Saga       │  │ Top-ups        │
        │                  │  │ Orchestr.  │  │                │
        └─────────┬────────┘  └──────┬─────┘  └────────────────┘
                  │                  │
                  │          ┌───────▼────────┐
                  │          │  Apache Kafka  │
                  │          │      :9092     │
                  │          │ Event Streaming│
                  │          │    Cluster     │
                  │          └───────┬────────┘
                  │                  │
              ┌───▼────────────────���─▼────────────┐
              │   Redis Cache :6379              │
              │   - Rate Limits                  │
              │   - Fraud Velocity Counters     │
              │   - OTP Storage                 │
              └────────────────────────────────┘

        ┌─────────────────────────────────────────┐
        │  Background Asynchronous Services       │
        │  ┌──────────────────────────────────┐   │
        │  │ Fraud Detection Service          │   │
        │  │ Real-time Risk Engine            │   │
        │  └─────────────────���────────────────┘   │
        │  ┌──────────────────────────────────┐   │
        │  │ Notification Service             │   │
        │  │ Alerts & SMS/Email Dispatcher    │   │
        │  └──────────────────────────────────┘   │
        └─────────────────────────────────────────┘
```

---

## 2. Technology Stack & Infrastructure

| Component | Technology | Role |
| :--- | :--- | :--- |
| **Framework** | Spring Boot 3.x, Spring Cloud | Core microservices framework |
| **API Gateway** | Spring Cloud Gateway (WebFlux) | Dynamic routing, resilience, Redis rate limiting |
| **Synchronous IPC** | Spring Cloud OpenFeign | Low-latency direct service-to-service communication |
| **Asynchronous Streaming** | Apache Kafka & Zookeeper | Distributed event log, decoupled messaging |
| **Distributed Cache & State** | Redis | Rate limiting, fraud velocity counters, running averages, OTP storage |
| **Data Persistence** | Spring Data JPA / Hibernate | Relational entity persistence and schema generation |
| **Containerization** | Docker & Docker Compose | Multi-container local orchestration |

---

## 3. Microservices Catalog & Ports

| Service Name | Port | Primary Responsibilities |
| :--- | :---: | :--- |
| **`api-gateway`** | `8080` | Reverse proxy, dynamic path routing, token-bucket rate limiting via Redis. |
| **`account-service`** | `8081` | Manages customer accounts, balances, account locking/unlocking, and daily limits. |
| **`transaction-service`** | `8082` | Orchestrates the Money Transfer Saga, manages transaction states, generates OTPs, and executes compensation. |
| **`payment-service`** | `8083` | Manages wallet top-ups and payment requests. |
| **`fraud-detection-service`** | *(Worker)* | Listens to initiated transactions, computes velocity/risk metrics, and flags anomalies. |
| **`notification-service`** | *(Worker)* | Listens to all financial and security events to deliver user notifications. |

---

## 4. Master Kafka Events & Topics Catalog

### Kafka Topic Flow Diagram

```
Transaction Service Events:
├── transaction.initiated
│   └─→ Fraud Detection Service (for screening)
├── transaction.otp.generated
│   └─→ Notification Service (send OTP)
├── transaction.completed
│   ├─→ Account Service (credit receiver)
│   └─→ Notification Service (send alerts)
├── fraud.detected
│   ├─→ Account Service (lock account)
│   └─→ Notification Service (security alert)
└── transaction.refunded
    └─→ Notification Service (refund alert)

Fraud Detection Service Events:
├── fraud.check.clean
│   └─→ Transaction Service (proceed with completion)
└── verification.required
    └─→ Transaction Service (flag for OTP)
```

### Event Payload & Contract Specifications

#### 1. `transaction.initiated`

- **Publisher:** `transaction-service`
- **Subscriber:** `fraud-detection-service`
- **Purpose:** Emitted right after the sender's account is debited to initiate fraud screening.
- **Payload Structure:**
  ```json
  {
    "transactionId": "txn-uuid-1234",
    "senderAccountNumber": "100000000001",
    "receiverAccountNumber": "100000000002",
    "amount": 2500.00,
    "description": "Rent payment"
  }
  ```

#### 2. `fraud.check.clean`

- **Publisher:** `fraud-detection-service`
- **Subscriber:** `transaction-service`
- **Purpose:** Confirms all 3 fraud screening rules passed successfully.
- **Payload Structure:**
  ```json
  {
    "transactionId": "txn-uuid-1234",
    "isFraud": false,
    "reason": null
  }
  ```

#### 3. `verification.required`

- **Publisher:** `fraud-detection-service`
- **Subscriber:** `transaction-service`
- **Purpose:** Alerts that a transaction violated a risk rule and requires 2-Factor OTP verification.
- **Payload Structure:**
  ```json
  {
    "transactionId": "txn-uuid-1234",
    "accountNumber": "100000000001",
    "amount": 25000.00,
    "reason": "Unusual transaction amount - exceeds 3X your average"
  }
  ```

#### 4. `transaction.otp.generated`

- **Publisher:** `transaction-service`
- **Subscriber:** `notification-service`
- **Purpose:** Requests delivery of a 6-digit one-time password to the user.
- **Payload Structure:**
  ```json
  {
    "transactionId": "txn-uuid-1234",
    "accountNumber": "100000000001",
    "amount": 25000.00,
    "otp": "481920",
    "reason": "Velocity limit exceeded"
  }
  ```

#### 5. `transaction.completed`

- **Publisher:** `transaction-service`
- **Subscribers:** `account-service`, `notification-service`
- **Purpose:** Marks the transaction completed; triggers the credit to the receiver's account and notifies both parties.
- **Payload Structure:**
  ```json
  {
    "transactionId": "txn-uuid-1234",
    "senderAccountNumber": "100000000001",
    "receiverAccountNumber": "100000000002",
    "amount": 2500.00,
    "description": "Rent payment"
  }
  ```

#### 6. `fraud.detected`

- **Publisher:** `transaction-service`
- **Subscribers:** `account-service`, `notification-service`
- **Purpose:** Triggered when an incorrect OTP is supplied; locks the sender's bank account for security.
- **Payload Structure:**
  ```json
  {
    "transactionId": "txn-uuid-1234",
    "accountNumber": "100000000001",
    "reason": "Wrong OTP entered - transaction cancelled, account blocked for security"
  }
  ```

#### 7. `transaction.refunded`

- **Publisher:** `transaction-service`
- **Subscriber:** `notification-service`
- **Purpose:** Emitted when compensation completes and debited funds are restored to the sender.
- **Payload Structure:**
  ```json
  {
    "transactionId": "txn-uuid-1234",
    "senderAccountNumber": "100000000001",
    "amount": 2500.00,
    "reason": "OTP expired - transaction cancelled and amount refunded"
  }
  ```

---

## 5. In-Memory State & Redis Strategy

Redis plays three critical roles across the architecture:

| Key Pattern | Data Type | TTL | Owning Service | Purpose |
| :--- | :---: | :---: | :--- | :--- |
| `Fraud:velocity{accountNumber}` | `Long` | 60 seconds | `fraud-detection-service` | Tracks the transaction velocity per minute. Exceeding threshold triggers verification. |
| `fraud.avg_amount{accountNumber}` | `String` (BigDecimal) | Persistent | `fraud-detection-service` | Maintains a running average of transfer amounts to detect anomalous spikes (e.g. > 3x average). |
| `verification:otp{transactionId}` | `String` | 5 minutes | `transaction-service` | Securely holds the generated 6-digit OTP until verified or expired. |
| `request_rate_limiter.{...}` | `Token Bucket` | Dynamic | `api-gateway` | Protects services against DDoS and throttling violations. |

---

## 6. End-to-End Execution Flows & Sequence Diagrams

---

### Flow 1: Account Creation & Management

**Process:**
1. Client submits account details (name, email, phone, account type, initial deposit) to `/api/v1/accounts`.
2. `account-service` verifies email uniqueness.
3. A secure 12-digit random account number is generated.
4. Transaction limits are set according to account type:
   - `SAVING`: 100,000
   - `CURRENT`: 500,000
5. The record is persisted with `ACTIVE` status and returned.

---

### Flow 2: P2P Money Transfer (Saga Pattern)

**Sequence Diagram:**

```
Sender (User)
    │
    ├─→ POST /api/v1/transactions/transfer
    │       │
    │       ▼
    │   API Gateway
    │       │
    │       ├─→ Forward to Transaction Service (SAGA ORCHESTRATOR)
    │       │       │
    │       │       ├─→ STEP 1: Synchronous Debit
    │       │       │   Feign Call: PUT /accounts/{sender}/deduct?amount=X
    │       │       │   Account Service: Balance Deducted ✓
    │       │       │
    │       │       ├─→ STEP 2: Save State
    │       │       │   Transaction Status: PROCESSING
    │       │       │
    │       │       ├─→ STEP 3: Async Fraud Screening
    │       │       │   Publish: transaction.initiated → Kafka
    │       │       │
    │       │       └─→ Return HTTP 201 (Status: PROCESSING)
    │       │
    │       └─→ HTTP 201 Created
    │
    └─ Acknowledge (Processing)

Kafka Bus receives: transaction.initiated
    │
    ▼
Fraud Detection Service
    │
    ├─→ Get Sender Balance (Feign)
    │
    ├─→ Run 3 Fraud Checks:
    │   1. Velocity Check: Redis count > maxPerMinute?
    │   2. Spike Check: Amount > 3X Running Average?
    │   3. Drain Check: Amount > 90% Account Balance?
    │
    └─→ IF ALL PASS:
        Publish: fraud.check.clean → Kafka
        │
        ▼
    Transaction Service receives: fraud.check.clean
        │
        ├─→ Update Status: COMPLETED
        │
        ├─→ Publish: transaction.completed → Kafka
        │
        └─→ Parallel Processing:
            ├─→ Account Service: Credit Receiver Balance
            └─→ Notification Service: Send Debit Alert (Sender) & Credit Alert (Receiver)
```

---

### Flow 3: Real-Time Fraud Detection Engine

Every initiated transaction undergoes **three concurrent rule checks** in `fraud-detection-service`:

```
Consume: transaction.initiated
    │
    ▼
Fetch Sender Live Balance (Feign Call)
    │
    ├─→ RULE 1: Velocity Check
    │   │
    │   ├─→ Query Redis: "Fraud:velocity{accountNumber}"
    │   │
    │   ├─→ IF count > maxPerMinute (e.g., 10)
    │   │   └─→ FLAG: "Velocity limit exceeded"
    │   │
    │   └─→ ELSE: Pass to Rule 2
    │
    ├─→ RULE 2: Spike Detection (Amount Anomaly)
    │   │
    │   ├─→ Query Redis: "fraud.avg_amount{accountNumber}"
    │   │
    │   ├─→ IF amount > (3 × running average)
    │   │   └─→ FLAG: "Unusual amount > 3X average"
    │   │
    │   └─→ ELSE: Pass to Rule 3
    │
    └─→ RULE 3: Account Drain Check
        │
        ├─→ IF amount > (0.9 × account balance)
        │   └─→ FLAG: "Exceeds 90% of account balance"
        │
        └─→ ELSE: Mark Transaction CLEAN

IF FLAGGED:
    Publish: verification.required → Kafka
    
IF CLEAN:
    Publish: fraud.check.clean → Kafka
```

---

### Flow 4: OTP Verification & Compensation (Saga Rollback)

When a transaction is flagged for suspicious activity:

```
Fraud Detection Service
    │
    ├─→ Publish: verification.required → Kafka
    │
    ▼
Transaction Service
    │
    ├─→ Generate 6-digit OTP
    ├─→ Update Status: PENDING_VERIFICATION
    ├─→ Store in Redis: verification:otp{txnId} (TTL: 5 mins)
    │
    ├─→ Publish: transaction.otp.generated → Kafka
    │
    ▼
Notification Service
    │
    └─→ Send OTP via SMS/Alert with Flag Reason

User receives OTP and submits verification:
    │
    ▼
POST /api/v1/transactions/{txnId}/verify?otp=XXXXXX

Transaction Service
    │
    ├─→ SCENARIO 1: Correct OTP
    │   ├─→ Delete OTP from Redis
    │   ├─→ Update Status: COMPLETED
    │   ├─→ Publish: transaction.completed → Kafka
    │   ├─→ Account Service: Credit Receiver
    │   └─→ Notification Service: Send Success Alerts
    │
    ├─→ SCENARIO 2: Incorrect OTP
    │   ├─→ Delete OTP from Redis
    │   ├─→ Publish: fraud.detected → Kafka
    │   │
    │   ├─→ COMPENSATION (Saga Rollback):
    │   │   ├─→ Account Service: Lock Sender Account
    │   │   ├─→ Account Service: Credit Sender (Refund)
    │   │   └─→ Notification Service: Security Alert
    │   │
    │   ├─→ Update Status: FLAGGED
    │   └─→ Publish: transaction.refunded → Kafka
    │       └─→ Notification Service: Alert Refund
    │
    └─→ SCENARIO 3: OTP Expired (> 5 mins)
        ├─→ COMPENSATION (Saga Rollback):
        │   ├─→ Account Service: Credit Sender (Refund)
        │   └─→ Notification Service: Auto-Refund Alert
        │
        ├─→ Update Status: FLAGGED
        └─→ Publish: transaction.refunded → Kafka
```

---

### Flow 5: Real-Time Multi-Channel Notification Alerts

The **Notification Service** acts as an isolated sink listening for system-wide domain events:

| Event | Action |
| :--- | :--- |
| `transaction.otp.generated` | Dispatches OTP and security warning via SMS/Email |
| `transaction.completed` | Dispatches simultaneous Debit alert to Sender and Credit alert to Receiver |
| `fraud.detected` | Dispatches immediate critical alert that the account is blocked |
| `transaction.refunded` | Dispatches cancellation and refund confirmation alert |

---

## 7. REST API Endpoint Directory

All endpoints are exposed through the **API Gateway** on port `8080`.

### 1. Account Service (`/api/v1/accounts`)

| Method | Endpoint | Description | Request Body / Params |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/accounts` | Open a new bank account | `CreateAccountRequest` JSON |
| `GET` | `/api/v1/accounts/{accountNumber}` | Retrieve account details | Path variable |
| `GET` | `/api/v1/accounts/{accountNumber}/balance` | Query current balance | Path variable |
| `PUT` | `/api/v1/accounts/{accountNumber}/block` | Manually/System lock an account | Path variable |
| `PUT` | `/api/v1/accounts/{accountNumber}/deduct` | Deduct funds (Internal / Saga) | `?amount=100.00` |
| `PUT` | `/api/v1/accounts/{accountNumber}/credit` | Credit funds (Internal / Saga) | `?amount=100.00` |

### 2. Transaction Service (`/api/v1/transactions`)

| Method | Endpoint | Description | Request Body / Params |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/transactions/transfer` | Initiate a P2P money transfer | `TransferRequest` JSON |
| `GET` | `/api/v1/transactions/{transactionId}` | Fetch status of a transaction | Path variable |
| `GET` | `/api/v1/transactions/account/{accountNumber}` | Fetch transaction statement/history | Path variable |
| `POST` | `/api/v1/transactions/{transactionId}/verify` | Submit OTP for flagged transfer | `?otp=123456` |

### 3. Payment Service (`/api/v1/payments`)

| Method | Endpoint | Description | Request Body / Params |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/payments/wallet/topup` | Top up wallet balance | `TopUpRequest` JSON |
| `GET` | `/api/v1/payments/wallet/{accountNumber}` | Get wallet balance | Path variable |

---

## 8. How to Run & Test the Ecosystem

### Step 1: Start Infrastructure Containers

Ensure Docker is running, then execute:

```bash
docker-compose up -d
```

This initializes:
- **Redis** on port `6379`
- **Zookeeper** on port `2181`
- **Apache Kafka** on port `9092`

### Step 2: Start Spring Boot Services

Start each microservice in the following order:

1. **account-service** (Port 8081)
   ```bash
   cd account-service
   mvn spring-boot:run
   ```

2. **transaction-service** (Port 8082)
   ```bash
   cd transaction-service
   mvn spring-boot:run
   ```

3. **fraud-detection-service**
   ```bash
   cd fraud-detection-service
   mvn spring-boot:run
   ```

4. **notification-service**
   ```bash
   cd notification-service
   mvn spring-boot:run
   ```

5. **payment-service** (Port 8083)
   ```bash
   cd payment-service
   mvn spring-boot:run
   ```

6. **api-gateway** (Port 8080)
   ```bash
   cd api-gatewayservice
   mvn spring-boot:run
   ```

### Step 3: Example End-to-End Test Journey

#### 1. Create Sender Account

```bash
curl -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{
    "accountHolderName": "Alice Smith",
    "email": "alice@example.com",
    "phone": "+1234567890",
    "accountType": "SAVING",
    "initialDeposit": 50000.00
  }'
```

**Response:**
```json
{
  "accountNumber": "100000000001",
  "accountHolderName": "Alice Smith",
  "email": "alice@example.com",
  "balance": 50000.00,
  "accountType": "SAVING",
  "accountStatus": "ACTIVE"
}
```

#### 2. Create Receiver Account

```bash
curl -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{
    "accountHolderName": "Bob Jones",
    "email": "bob@example.com",
    "phone": "+1987654321",
    "accountType": "SAVING",
    "initialDeposit": 1000.00
  }'
```

**Response:**
```json
{
  "accountNumber": "100000000002",
  "accountHolderName": "Bob Jones",
  "email": "bob@example.com",
  "balance": 1000.00,
  "accountType": "SAVING",
  "accountStatus": "ACTIVE"
}
```

#### 3. Perform Normal Transfer (Clean Flow - No Fraud)

```bash
curl -X POST http://localhost:8080/api/v1/transactions/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "senderAccountNumber": "100000000001",
    "receiverAccountNumber": "100000000002",
    "amount": 500.00,
    "description": "Lunch split"
  }'
```

**Expected Flow:**
- Transaction initiated, sender's account debited
- Fraud detection service scans (all checks pass)
- Transaction marked COMPLETED
- Receiver's account credited
- Both parties receive success notifications

**Response:**
```json
{
  "transactionId": "txn-abc-123",
  "status": "COMPLETED",
  "senderAccountNumber": "100000000001",
  "receiverAccountNumber": "100000000002",
  "amount": 500.00,
  "description": "Lunch split"
}
```

#### 4. Trigger Fraud Check (> 90% Balance or High Velocity)

```bash
curl -X POST http://localhost:8080/api/v1/transactions/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "senderAccountNumber": "100000000001",
    "receiverAccountNumber": "100000000002",
    "amount": 48000.00,
    "description": "Large wire transfer"
  }'
```

**Expected Flow:**
- Transaction initiated, sender's account debited
- Fraud detection service flags: "Exceeds 90% of account balance"
- Transaction status set to `PENDING_VERIFICATION`
- OTP generated and sent to user (check logs in notification-service)
- Transaction waits for verification

**Response:**
```json
{
  "transactionId": "txn-def-456",
  "status": "PENDING_VERIFICATION",
  "senderAccountNumber": "100000000001",
  "receiverAccountNumber": "100000000002",
  "amount": 48000.00,
  "description": "Large wire transfer",
  "reason": "Exceeds 90% of account balance - OTP verification required"
}
```

#### 5. Submit OTP for Verification (Success Case)

Look at the **notification-service** logs to find the generated OTP (e.g., `481920`)

```bash
curl -X POST "http://localhost:8080/api/v1/transactions/txn-def-456/verify?otp=481920"
```

**Expected Outcome:**
- OTP validated
- Transaction status changed to `COMPLETED`
- Receiver's account credited
- Both parties notified of successful transfer

**Response:**
```json
{
  "transactionId": "txn-def-456",
  "status": "COMPLETED",
  "message": "OTP verified. Transaction completed successfully."
}
```

#### 6. Submit Wrong OTP (Failure Case - Saga Compensation)

```bash
curl -X POST "http://localhost:8080/api/v1/transactions/txn-def-456/verify?otp=999999"
```

**Expected Outcome:**
- OTP validation fails
- Fraud detected event published
- Sender's account locked (security measure)
- Debited amount refunded to sender
- Transaction status changed to `FLAGGED`
- Critical security alert sent to user

**Response:**
```json
{
  "transactionId": "txn-def-456",
  "status": "FLAGGED",
  "message": "Incorrect OTP. Account locked for security. Amount refunded.",
  "refundedAmount": 48000.00
}
```

#### 7. Check Account Details & Balance

```bash
curl -X GET "http://localhost:8080/api/v1/accounts/100000000001"
```

**Response:**
```json
{
  "accountNumber": "100000000001",
  "accountHolderName": "Alice Smith",
  "email": "alice@example.com",
  "balance": 1500.00,
  "dailyLimit": 100000.00,
  "accountType": "SAVING",
  "accountStatus": "ACTIVE"
}
```

#### 8. View Transaction History

```bash
curl -X GET "http://localhost:8080/api/v1/transactions/account/100000000001"
```

**Response:**
```json
[
  {
    "transactionId": "txn-abc-123",
    "status": "COMPLETED",
    "amount": 500.00,
    "description": "Lunch split",
    "timestamp": "2026-10-20T10:30:00Z"
  },
  {
    "transactionId": "txn-def-456",
    "status": "FLAGGED",
    "amount": 48000.00,
    "description": "Large wire transfer",
    "timestamp": "2026-10-20T10:35:00Z"
  }
]
```

---

## Key Features Summary

✅ **Real-Time Fraud Detection:**
- Velocity-based fraud checks
- Amount anomaly detection (3X average)
- Account drain protection (90% balance)

✅ **Distributed Saga Pattern:**
- Automatic transaction rollback on failure
- Compensation logic for refunds
- Idempotent transaction handling

✅ **Multi-Level Security:**
- OTP-based 2FA for suspicious transactions
- Account locking on fraud detection
- Automatic fund refunds on verification failure

✅ **Event-Driven Architecture:**
- Asynchronous Kafka-based messaging
- Loose coupling between services
- Real-time notification delivery

✅ **High Availability:**
- Horizontal scaling of stateless services
- Redis caching for performance
- Rate limiting via API Gateway

---

## Monitoring & Debugging

### View Kafka Topics

```bash
docker exec -it kafka kafka-topics --list --bootstrap-server localhost:9092
```

### Monitor Kafka Messages

```bash
docker exec -it kafka kafka-console-consumer --topic transaction.initiated \
  --bootstrap-server localhost:9092 --from-beginning
```

### Check Redis State

```bash
docker exec -it redis redis-cli
> KEYS *
> GET Fraud:velocity:100000000001
> GET fraud.avg_amount:100000000001
```

### Service Logs

Each service writes logs to its console. Monitor logs for:
- `Transaction Service`: Saga state transitions
- `Fraud Detection Service`: Rule checks and flag decisions
- `Notification Service`: OTP and alert deliveries
- `Account Service`: Balance updates and account locks

---

**Document Version:** 1.0  
**Last Updated:** October 20, 2026  
**Project:** Digital Banking System with Microservices Architecture

